package net.cassite.vproxy.util.ringbuffer;

import net.cassite.vproxy.util.LogType;
import net.cassite.vproxy.util.Logger;
import net.cassite.vproxy.util.RingBuffer;
import net.cassite.vproxy.util.RingBufferETHandler;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Deque;
import java.util.LinkedList;
import java.util.function.Consumer;

/**
 * the ring buffer which contains SSLEngine<br>
 * this buffer is for sending data:<br>
 * encrypted bytes will be stored into this buffer<br>
 * and will be converted to plain bytes
 * which can be retrieved by user
 */
public class SSLUnwrapRingBuffer extends AbstractRingBuffer implements RingBuffer {
    class WritableHandler implements RingBufferETHandler {
        @Override
        public void readableET() {
            triggerReadable(); // proxy the event
        }

        @Override
        public void writableET() {
            generalUnwrap();
        }
    }

    private /*might be replaced when switching*/ ByteBufferRingBuffer plainBufferForApp;
    private final SimpleRingBuffer encryptedBufferForInput;
    private final SSLEngine engine;
    private final Consumer<Runnable> resumer;
    private final Deque<Runnable> deferResumer = new LinkedList<>();
    private final WritableHandler writableHandler = new WritableHandler();

    // will call the pair's wrap/wrapHandshake when need to send data
    private final SSLWrapRingBuffer pair;

    private boolean closed = false;

    SSLUnwrapRingBuffer(ByteBufferRingBuffer plainBufferForApp,
                        int inputCap,
                        SSLEngine engine,
                        Consumer<Runnable> resumer,
                        SSLWrapRingBuffer pair) {
        this.plainBufferForApp = plainBufferForApp;
        this.engine = engine;
        this.resumer = resumer;
        this.pair = pair;

        // we add a handler to the plain buffer
        plainBufferForApp.addHandler(writableHandler);

        // use heap buffer
        // it will interact heavily with java code
        this.encryptedBufferForInput = RingBuffer.allocate(inputCap);
    }

    @Override
    public int storeBytesFrom(ReadableByteChannel channel) throws IOException {
        if (closed) {
            return 0; // don't store anything it's already closed
        }
        int read = encryptedBufferForInput.storeBytesFrom(channel);
        if (read == 0) {
            return 0; // maybe the buffer is full
        }
        if (read == -1) {
            assert Logger.lowLevelDebug("reading from remote return -1");
            return -1;
        }
        // got new data, let's unwrap it
        generalUnwrap();
        return read;
    }

    // -------------------
    // helper functions BEGIN
    // -------------------
    private void deferDefragmentInput() {
        deferResumer.push(() -> {
            encryptedBufferForInput.defragment();
            generalUnwrap();
        });
    }

    private void deferDefragmentApp() {
        deferResumer.push(() -> {
            plainBufferForApp.defragment();
            generalUnwrap();
        });
    }

    private void deferGeneralUnwrap() {
        deferResumer.push(this::generalUnwrap);
    }

    private void deferGeneralWrap() {
        deferResumer.add(pair::generalWrap);
    }

    private void resumeGeneralUnwrap() {
        resumer.accept(this::generalUnwrap);
    }

    private void resumeGeneralWrap() {
        resumer.accept(pair::generalWrap);
    }
    // -------------------
    // helper functions END
    // -------------------

    private void generalUnwrap() {
        if (encryptedBufferForInput.used() == 0)
            return; // no data for unwrapping
        assert Logger.lowLevelDebug(
            "encryptedBufferForInput has " + encryptedBufferForInput.used() + " bytes BEFORE unwrap");
        boolean inBufferWasFull = encryptedBufferForInput.free() == 0;
        setOperating(true);
        try {
            encryptedBufferForInput.operateOnByteBufferWriteOut(Integer.MAX_VALUE,
                encryptedBuffer -> plainBufferForApp.operateOnByteBufferStoreIn(plainBuffer -> {
                    SSLEngineResult result;
                    try {
                        result = engine.unwrap(encryptedBuffer, plainBuffer);
                    } catch (SSLException e) {
                        Logger.error(LogType.SSL_ERROR, "got error when unwrapping", e);
                        return false;
                    }
                    assert Logger.lowLevelDebug("unwrap: " + result);
                    if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                        return unwrap(result);
                    } else {
                        return unwrapHandshake(result);
                    }
                }));
        } catch (IOException e) {
            // it's memory operation, should not happen
            Logger.shouldNotHappen("got exception when unwrapping", e);
        } finally {
            assert Logger.lowLevelDebug(
                "encryptedBufferForInput has " + encryptedBufferForInput.used() + " bytes AFTER unwrap");
            boolean inBufferNowNotFull = encryptedBufferForInput.free() > 0;
            if (inBufferWasFull && inBufferNowNotFull) {
                triggerWritable();
            }
            setOperating(false);
        }

        // might need instantly resume
        if (deferResumer.isEmpty())
            return; // nothing to resume now
        assert deferResumer.size() == 1;
        deferResumer.poll().run();
    }

    private boolean unwrapHandshake(SSLEngineResult result) {
        assert Logger.lowLevelDebug("unwrapHandshake: " + result);
        if (result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
            Logger.warn(LogType.SSL_ERROR, "BUFFER_UNDERFLOW for handshake unwrap, " +
                "expecting " + engine.getSession().getPacketBufferSize());
            if (encryptedBufferForInput.canDefragment()) {
                deferDefragmentInput();
            } // otherwise we wait until data is provided
            return false;
        }

        SSLEngineResult.HandshakeStatus status = result.getHandshakeStatus();
        if (status == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            Logger.shouldNotHappen("ssl unwrap handshake got NOT_HANDSHAKING");
            return false;
        }
        if (status == SSLEngineResult.HandshakeStatus.FINISHED) {
            assert Logger.lowLevelDebug("handshake finished");
            deferGeneralWrap();
            return true; // done
        }
        if (status == SSLEngineResult.HandshakeStatus.NEED_TASK) {
            assert Logger.lowLevelDebug("ssl engine returns NEED_TASK");
            new Thread(() -> {
                assert Logger.lowLevelDebug("TASK begins");
                Runnable r;
                while ((r = engine.getDelegatedTask()) != null) {
                    r.run();
                }
                assert Logger.lowLevelDebug("ssl engine returns " + engine.getHandshakeStatus() + " after task");
                if (engine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                    resumeGeneralWrap();
                } else if (engine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
                    resumeGeneralWrap();
                } else {
                    resumeGeneralUnwrap();
                }
            }).start();
            return true;
        }
        if (status == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
            // should call the pair to wrap
            pair.generalWrap();
            return true;
        }
        assert status == SSLEngineResult.HandshakeStatus.NEED_UNWRAP;
        // call again, in case some data not unwrapped
        deferGeneralUnwrap();
        return true;
    }

    private boolean unwrap(SSLEngineResult result) {
        assert Logger.lowLevelDebug("unwrap: " + result);
        SSLEngineResult.Status status = result.getStatus();
        if (status == SSLEngineResult.Status.CLOSED) {
            Logger.shouldNotHappen("the unwrapping returned CLOSED");
            return false;
        }
        if (status == SSLEngineResult.Status.OK) {
            deferGeneralUnwrap(); // in case some data left in input buffer
            return true; // done
        }
        if (status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
            if (engine.getSession().getPacketBufferSize() > encryptedBufferForInput.capacity()) {
                Logger.warn(LogType.SSL_ERROR, "BUFFER_UNDERFLOW in unwrap, " +
                    "expecting " + engine.getSession().getPacketBufferSize());
            } else {
                assert Logger.lowLevelDebug("BUFFER_UNDERFLOW in unwrap, " +
                    "expecting " + engine.getSession().getPacketBufferSize());
            }
            if (encryptedBufferForInput.canDefragment()) {
                deferDefragmentInput(); // defragment to try to make more space for net flow input
                // NOTE: if it's capacity is smaller than required, we can do nothing here
            } // otherwise we need to wait for more data
            return true;
        }
        if (status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
            Logger.warn(LogType.SSL_ERROR, "BUFFER_OVERFLOW in unwrap, " +
                "expecting " + engine.getSession().getApplicationBufferSize());
            deferDefragmentApp(); // defragment to try to make more space
            // NOTE: if it's capacity is smaller than required, we can do nothing here
            return false;
        }
        // should have no other status when reaches here
        Logger.shouldNotHappen("should not reach here, but it's ok");
        return true;
    }

    @Override
    public int writeTo(WritableByteChannel channel, int maxBytesToWrite) throws IOException {
        // proxy the operation from plain buffer
        return plainBufferForApp.writeTo(channel, maxBytesToWrite);
    }

    @Override
    public int free() {
        // whether have space to store data is determined by network input buffer
        return encryptedBufferForInput.free();
    }

    @Override
    public int used() {
        // user may use this to check whether the buffer still had data left
        return plainBufferForApp.used();
    }

    @Override
    public int capacity() {
        // why use app buffer
        // see comments in SSLWrapRingBuffer#capacity
        return plainBufferForApp.capacity();
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public void clean() {
        plainBufferForApp.clean();
        // the input buffer is not direct
        // so does not need to be cleaned
    }

    @Override
    public void clear() {
        plainBufferForApp.clear();
    }

    @Override
    public RingBuffer switchBuffer(RingBuffer buf) throws RejectSwitchException {
        if (plainBufferForApp.used() != 0)
            throw new RejectSwitchException("the plain buffer is not empty");
        if (!(buf instanceof ByteBufferRingBuffer))
            throw new RejectSwitchException("the input is not a ByteBufferRingBuffer");

        // switch buffers and handlers
        plainBufferForApp.removeHandler(writableHandler);
        plainBufferForApp = (ByteBufferRingBuffer) buf;
        plainBufferForApp.addHandler(writableHandler);

        // try to unwrap any data if presents
        generalUnwrap();

        return this;
    }
}
