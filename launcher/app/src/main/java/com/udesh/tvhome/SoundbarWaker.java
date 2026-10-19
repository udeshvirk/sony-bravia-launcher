package com.udesh.tvhome;

import android.content.Context;
import android.util.Base64;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Turns HDMI-CEC System Audio Mode on (wakes the ARC soundbar) by asking the TV's own adbd
 * (127.0.0.1 only) to run exactly one fixed command: "cmd hdmi_control setsam on".
 * No other command can be run through this class. Apps can't send CEC themselves on Android 12;
 * the ADB shell can. The ADB key lives in the app's private storage and the TV asks once to allow it.
 */
final class SoundbarWaker {
    private static final byte[] COMMAND = "shell:cmd hdmi_control setsam on\0".getBytes(StandardCharsets.UTF_8);
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03,
            0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private final Context ctx;

    SoundbarWaker(Context c) { ctx = c.getApplicationContext(); }

    /**
     * Sends the wake-up. Blocks; call off the main thread. authTimeoutMs is how long to wait for
     * the one-time "Allow USB debugging?" answer on the TV. Returns true if the TV reported success.
     */
    boolean wake(int authTimeoutMs) throws Exception {
        KeyPair kp = keys();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 2000);
            s.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            send(out, CNXN, 0x01000001, 256 * 1024, "host::\0".getBytes(StandardCharsets.UTF_8));

            boolean sentSig = false;
            while (true) {
                Msg m = read(in);
                if (m.cmd == CNXN) break;
                if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected reply");
                if (!sentSig) {
                    send(out, AUTH, AUTH_SIGNATURE, 0, sign(kp.getPrivate(), m.data));
                    sentSig = true;
                } else {
                    send(out, AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob((RSAPublicKey) kp.getPublic()));
                    s.setSoTimeout(authTimeoutMs);
                }
            }
            s.setSoTimeout(8000);
            send(out, OPEN, 1, 0, COMMAND);
            StringBuilder reply = new StringBuilder();
            int remote = 0;
            while (true) {
                Msg m = read(in);
                if (m.cmd == OKAY) { remote = m.arg0; continue; }
                if (m.cmd == WRTE) { reply.append(new String(m.data, StandardCharsets.UTF_8)); send(out, OKAY, 1, m.arg0, new byte[0]); continue; }
                if (m.cmd == CLSE) { send(out, CLSE, 1, remote, new byte[0]); break; }
            }
            return reply.toString().contains("Success");
        }
    }

    // ---- ADB wire protocol ----

    private static final class Msg { int cmd, arg0; byte[] data; }

    private static void send(OutputStream out, int cmd, int a0, int a1, byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte x : data) sum += x & 0xff;
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        out.write(b.array());
        out.flush();
    }

    private static Msg read(DataInputStream in) throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt(); m.arg0 = b.getInt(); b.getInt();
        int len = b.getInt();
        if (len < 0 || len > 1 << 20) throw new IOException("bad length");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    // ---- key ----

    private KeyPair keys() throws Exception {
        File priv = new File(ctx.getFilesDir(), "adbkey"), pub = new File(ctx.getFilesDir(), "adbkey.pub.der");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub.toPath()))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv.toPath()))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        Files.write(priv.toPath(), kp.getPrivate().getEncoded());
        Files.write(pub.toPath(), kp.getPublic().getEncoded());
        return kp;
    }

    private static byte[] sign(PrivateKey k, byte[] token) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, k);
        c.update(SHA1_PREFIX);
        return c.doFinal(token);
    }

    /** Android's mincrypt RSAPublicKey format (what adbd expects), base64 plus a name. */
    private static byte[] publicKeyBlob(RSAPublicKey k) {
        final int words = 64; // 2048-bit
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n = k.getModulus();
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 8 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt(n0inv.intValue());
        for (int i = 0; i < words; i++) b.putInt(n.shiftRight(32 * i).mod(r32).intValue());
        for (int i = 0; i < words; i++) b.putInt(rr.shiftRight(32 * i).mod(r32).intValue());
        b.putInt(k.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " tvhome-soundbar@BRAVIA\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
