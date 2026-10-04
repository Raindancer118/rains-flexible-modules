package de.raindancer.e2e;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * The server console over RCON — what a scenario uses for anything a player would not do: op a bot,
 * set a setting, kill somebody, stop the server. One connection, one command at a time.
 */
public final class Rcon implements AutoCloseable {

    private static final int LOGIN = 3;
    private static final int COMMAND = 2;

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextId = 1;

    private Rcon(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    public static Rcon connect(String host, int port, String password) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setSoTimeout(30_000);
        Rcon rcon = new Rcon(socket);
        int id = rcon.send(LOGIN, password);
        int answered = rcon.readId();
        if (answered != id) {
            rcon.close();
            throw new IOException("RCON refused the password");
        }
        return rcon;
    }

    /** Runs {@code command} (without the slash) and returns what it said, colour codes taken out. */
    public synchronized String run(String command) throws IOException {
        int id = send(COMMAND, command.startsWith("/") ? command.substring(1) : command);
        String answer = readBody(id);
        return answer.replaceAll("§x(§[0-9a-fA-F]){6}", "").replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }

    private int send(int type, String body) throws IOException {
        int id = nextId++;
        byte[] text = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer packet = ByteBuffer.allocate(14 + text.length).order(ByteOrder.LITTLE_ENDIAN);
        packet.putInt(10 + text.length).putInt(id).putInt(type).put(text).put((byte) 0).put((byte) 0);
        out.write(packet.array());
        out.flush();
        return id;
    }

    private int readId() throws IOException {
        int length = Integer.reverseBytes(in.readInt());
        int id = Integer.reverseBytes(in.readInt());
        in.readInt();
        in.readFully(new byte[length - 8]);
        return id;
    }

    private String readBody(int expectedId) throws IOException {
        int length = Integer.reverseBytes(in.readInt());
        int id = Integer.reverseBytes(in.readInt());
        in.readInt();
        byte[] rest = new byte[length - 8];
        in.readFully(rest);
        if (id != expectedId) {
            throw new IOException("RCON answered request " + id + " while " + expectedId + " was waiting");
        }
        return new String(rest, 0, Math.max(0, rest.length - 2), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing a connection that is already gone is not a failure of the scenario
        }
    }
}
