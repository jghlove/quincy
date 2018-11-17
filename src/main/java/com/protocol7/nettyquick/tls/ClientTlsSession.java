package com.protocol7.nettyquick.tls;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.protocol7.nettyquick.tls.extensions.ExtensionType;
import com.protocol7.nettyquick.tls.extensions.KeyShare;
import com.protocol7.nettyquick.tls.extensions.SupportedVersions;
import com.protocol7.nettyquick.tls.extensions.TransportParameters;
import com.protocol7.nettyquick.utils.Bytes;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.Optional;

public class ClientTlsSession {

    private static final HashFunction SHA256 = Hashing.sha256();

    private KeyExchange kek;

    private ByteBuf handshakeBuffer;
    private byte[] clientHello;
    private byte[] serverHello;
    private byte[] handshakeSecret;

    public ClientTlsSession() {
        reset();
    }

    public void reset() {
        kek = KeyExchange.generate(Group.X25519);
        handshakeBuffer = Unpooled.buffer();
        clientHello = null;
        serverHello = null;
        handshakeSecret = null;
    }

    public byte[] start() {
        if (clientHello != null) {
            throw new IllegalStateException("Already started");
        }

        ClientHello ch = ClientHello.defaults(kek, TransportParameters.defaults());
        ByteBuf bb = Unpooled.buffer();
        ch.write(bb);

        clientHello = Bytes.asArray(bb);
        return clientHello;
    }

    public AEAD handleServerHello(byte[] msg) {
        if (clientHello == null) {
            throw new IllegalStateException("Not started");
        }

        serverHello = msg;

        ByteBuf bb = Unpooled.wrappedBuffer(msg);
        ServerHello hello = ServerHello.parse(bb);

        SupportedVersions version = (SupportedVersions) hello.geExtension(ExtensionType.supported_versions).orElseThrow(IllegalArgumentException::new);
        if (!version.equals(SupportedVersions.TLS13)) {
            throw new IllegalArgumentException("Illegal version");
        }

        KeyShare keyShareExtension = (KeyShare) hello.geExtension(ExtensionType.key_share).orElseThrow(IllegalArgumentException::new);
        byte[] peerPublicKey = keyShareExtension.getKey(Group.X25519).get();
        byte[] sharedSecret = kek.generateSharedSecret(peerPublicKey);

        byte[] helloHash = SHA256.hashBytes(Bytes.concat(clientHello, serverHello)).asBytes();

        handshakeSecret = HKDFUtil.calculateHandshakeSecret(sharedSecret);

        return HandshakeAEAD.create(handshakeSecret, helloHash, true, true);
    }

    public synchronized Optional<HandshakeResult> handleHandshake(byte[] msg) {
        if (clientHello == null || serverHello == null) {
            throw new IllegalStateException("Got handshake in unexpected state");
        }

        handshakeBuffer.writeBytes(msg);

        handshakeBuffer.markReaderIndex();
        try {
            ServerHandshake handshake = ServerHandshake.parse(handshakeBuffer);

            // TODO verify handshake

            handshakeBuffer.resetReaderIndex();

            byte[] hs = Bytes.asArray(handshakeBuffer);

            byte[] helloHash = SHA256.hashBytes(Bytes.concat(clientHello, serverHello)).asBytes();
            byte[] handshakeHash = SHA256.hashBytes(Bytes.concat(clientHello, serverHello, hs)).asBytes();

            AEAD aead = OneRttAEAD.create(handshakeSecret, handshakeHash, true, true);

            handshakeBuffer = Unpooled.buffer();

            byte[] clientHandshakeTrafficSecret = HKDFUtil.expandLabel(handshakeSecret, "tls13 ","c hs traffic", helloHash, 32);

            ClientFinished clientFinished = ClientFinished.create(clientHandshakeTrafficSecret, handshakeHash, false);

            ByteBuf finBB = Unpooled.buffer();
            clientFinished.write(finBB);
            byte[] fin = Bytes.asArray(finBB);

            return Optional.of(new HandshakeResult(fin, aead));
        } catch (IndexOutOfBoundsException e) {
            // wait for more data
            System.out.println("Need more data, waiting...");
            handshakeBuffer.resetReaderIndex();

            return Optional.empty();
        }
    }

    public static class HandshakeResult {
        private final byte[] fin;
        private final AEAD oneRttAead;

        public HandshakeResult(byte[] fin, AEAD oneRttAead) {
            this.fin = fin;
            this.oneRttAead = oneRttAead;
        }

        public byte[] getFin() {
            return fin;
        }

        public AEAD getOneRttAead() {
            return oneRttAead;
        }
    }
}