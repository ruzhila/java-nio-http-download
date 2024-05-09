
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Downloader {
    static public class NioHTTPClient {
        public interface HTTPResponseListener {
            void onResponse(int statusCode, String statusText, Map<String, String> headers) throws IOException;

            void onData(byte[] buffer) throws IOException;
        }

        public static final String CRLF = "\r\n";
        private Selector selector;
        private SocketChannel socketChannel;
        public HTTPResponseListener listener;
        private String request;
        private ByteBuffer buffer;
        private boolean isResponseParsed = false;

        public NioHTTPClient(Selector selector) {
            this.selector = selector;
        }

        public void cancel() throws IOException {
            socketChannel.keyFor(selector).cancel();
            selector.selectNow();
            this.socketChannel.close();
        }

        public void sendRequest(String method, String url, ArrayList<String> headers) throws IOException {
            this.socketChannel = SocketChannel.open();
            socketChannel.configureBlocking(false);
            URL u = URI.create(url).toURL();
            int port = u.getPort() == -1 ? u.getDefaultPort() : u.getPort();
            socketChannel.connect(new InetSocketAddress(u.getHost(), port));
            String path = u.getPath().isBlank() ? "/" : u.getPath();
            path += u.getQuery() == null ? "" : "?" + u.getQuery();
            request = method + " " + path + " HTTP/1.1" + CRLF;
            request += "Host: " + u.getHost() + CRLF + "User-Agent: NioHTTPDownload/1.0" + CRLF;
            for (String header : headers) {
                request += header + CRLF;
            }
            request += CRLF;
            socketChannel.register(this.selector, SelectionKey.OP_CONNECT).attach(this);
        }

        public void onConnect(SelectionKey key) throws IOException {
            if (socketChannel.finishConnect()) {
                buffer = ByteBuffer.allocate(8192);
                key.interestOps(SelectionKey.OP_WRITE);
            }
        }

        public void onCanWrite(SelectionKey key) throws IOException {
            if (request != null) {
                socketChannel.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)));
                key.interestOps(SelectionKey.OP_READ);
                request = null;
            }
        }

        public void onCanRead(SelectionKey key) throws IOException {
            if (socketChannel.read(buffer) == -1) {
                cancel();
                return;
            }
            buffer.flip();
            if (!isResponseParsed) {
                int start = 0;
                while (start < buffer.limit() - 3) {
                    if (buffer.get(start) == '\r' && buffer.get(start + 1) == '\n' && buffer.get(start + 2) == '\r'
                            && buffer.get(start + 3) == '\n') {
                        start += 4;
                        break;
                    }
                    start++;
                }
                if (start == buffer.limit() - 3) {
                    return;
                }
                byte[] response = new byte[start];
                buffer.get(response);
                String[] lines = new String(response, StandardCharsets.UTF_8).split(CRLF);
                if (lines.length < 1) {
                    cancel();
                    return;
                }
                String[] statusLine = lines[0].split(" ");
                Map<String, String> headers = new java.util.HashMap<>();
                for (int i = 1; i < lines.length; i++) {
                    String[] pair = lines[i].split(":");
                    headers.put(pair[0].trim().toLowerCase(), pair[1].trim());
                }
                listener.onResponse(Integer.parseInt(statusLine[1]), statusLine[2], headers);
                isResponseParsed = true;
            }
            if (buffer.hasRemaining()) {
                byte remaining[] = new byte[buffer.remaining()];
                buffer.get(remaining);
                listener.onData(remaining);
                buffer.clear();
            }
        }
    }

    public static void main(String[] args) throws IOException {
        String url = "http://ruzhila.cn/favicon.ico";
        String output = "favicon.ico";

        if (args.length > 0) {
            url = args[0];
        }
        if (args.length > 1) {
            output = args[1];
        }
        final String outputFinal = output;
        Selector selector = Selector.open();
        NioHTTPClient downloadRequest = new NioHTTPClient(selector);
        downloadRequest.listener = new NioHTTPClient.HTTPResponseListener() {
            private int remaining = -1;
            private int contentLength = -1;
            private java.io.FileOutputStream fileOutputStream;

            @Override
            public void onResponse(int statusCode, String statusText,
                    Map<String, String> headers) throws IOException {
                if (headers.containsKey("content-length")) {
                    remaining = Integer.parseInt(headers.get("content-length"));
                    contentLength = remaining;
                }
                System.out.println("Downloading..." + contentLength);
                fileOutputStream = new java.io.FileOutputStream(outputFinal);
            }

            @Override
            public void onData(byte[] buffer) throws IOException {
                remaining -= buffer.length;
                System.out.println("Remaining: " + remaining + "/" + contentLength + " bytes");
                fileOutputStream.write(buffer);
                if (remaining <= 0) {
                    System.out.println("Download completed");
                    downloadRequest.cancel();
                }
            }
        };
        downloadRequest.sendRequest("GET", url, new ArrayList<>());

        while (selector.keys().size() > 0) {
            selector.select();
            for (SelectionKey key : selector.selectedKeys()) {
                NioHTTPClient client = (NioHTTPClient) key.attachment();
                if (key.isConnectable()) {
                    client.onConnect(key);
                } else if (key.isWritable()) {
                    client.onCanWrite(key);
                } else if (key.isReadable()) {
                    client.onCanRead(key);
                }
            }
            selector.selectedKeys().clear();
        }
    }
}