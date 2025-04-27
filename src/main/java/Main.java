import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

public class Main {
  static String basePath;
  static Set<String> supportedEncodings = new HashSet<String>() {{
    add("gzip");
  }};

  public static void main(String[] args) {
    parseBasePath(args);
    startServer();
  }

  private static void parseBasePath(String[] args) {
    basePath = null;
    for (int i = 0; i < args.length - 1; i++) {
      if (args[i].equals("--directory")) {
        basePath = args[i + 1];
        break;
      }
    }
  }

  private static void startServer() {
    ServerSocket serverSocket = createServerSocket();
    acceptConnections(serverSocket);
  }

  private static ServerSocket createServerSocket() {
    try {
      ServerSocket serverSocket = new ServerSocket(4221);
      serverSocket.setReuseAddress(true);
      return serverSocket;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static void acceptConnections(ServerSocket serverSocket) {
    while (true) {
      try {
        Socket socket = serverSocket.accept();
        new Thread(new RequestHandler(socket)).start();
      } catch (IOException e) {
        System.out.println("IOException while accepting connection: " + e.getMessage());
      }
    }
  }
}

class RequestHandler implements Runnable {
  private static final String HTTP_OK = "HTTP/1.1 200 OK";
  private static final String HTTP_CREATED = "HTTP/1.1 201 Created";
  private static final String HTTP_NOT_FOUND = "HTTP/1.1 404 Not Found";
  private static final String HTTP_METHOD_NOT_ALLOWED = "HTTP/1.1 405 Method Not Allowed";
  private static final String CRLF = "\r\n";

  private final Socket socket;

  public RequestHandler(Socket socket) {
    this.socket = socket;
  }

  @Override
  public void run() {
    try (
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            OutputStream out = socket.getOutputStream()
    ) {
      boolean keepConnectionAlive = true;

      while (keepConnectionAlive) {
        String requestLine = in.readLine();

        if (requestLine == null || requestLine.isEmpty()) {
          break;
        }

        String[] requestLineContents = requestLine.split(" ");
        if (requestLineContents.length < 2) {
          sendResponse(out, HTTP_METHOD_NOT_ALLOWED + CRLF + CRLF);
          break;
        }

        String method = requestLineContents[0];
        String urlPath = requestLineContents[1];

        // Parse headers
        String userAgent = "";
        int contentLength = 0;
        boolean connectionClose = false;
        Set<String> acceptEncoding = new HashSet<>();

        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
          String lowerLine = line.toLowerCase();
          if (lowerLine.startsWith("content-length:")) {
            contentLength = Integer.parseInt(line.split(":", 2)[1].trim());
          } else if (lowerLine.startsWith("user-agent:")) {
            userAgent = line.split(":", 2)[1].trim();
          } else if (lowerLine.startsWith("connection:")) {
            if (lowerLine.contains("close")) {
              connectionClose = true;
            }
          } else if (lowerLine.startsWith("accept-encoding:")) {
            acceptEncoding = Arrays.stream(line.split(":", 2)[1].split(","))
                    .map(String::trim)
                    .collect(Collectors.toSet());
          }
        }

        if (Objects.equals(method, "POST")) {
          handlePostRequest(in, out, urlPath, contentLength, connectionClose);
        } else if (Objects.equals(method, "GET")) {
          handleGetRequest(out, urlPath, userAgent, connectionClose, acceptEncoding);
        } else {
          sendResponse(out, HTTP_METHOD_NOT_ALLOWED + CRLF + CRLF);
        }

        out.flush();

        if (connectionClose) {
          keepConnectionAlive = false;
        }
      }
    } catch (IOException e) {
      System.out.println("IOException while handling connection: " + e.getMessage());
    } finally {
      try {
        socket.close();
      } catch (IOException ignored) {
      }
    }
  }

  private void handlePostRequest(BufferedReader in, OutputStream out, String urlPath, int contentLength, boolean connectionClose) throws IOException {
    if (urlPath.startsWith("/files/")) {
      String fileName = urlPath.substring("/files/".length());
      String requestBody = readRequestBody(in, contentLength);

      writeToFile(fileName, requestBody);
      String response = HTTP_CREATED + CRLF;
      if (connectionClose) {
        response += "Connection: close" + CRLF;
      }
      response += CRLF;
      sendResponse(out, response);
    } else {
      sendResponse(out, HTTP_NOT_FOUND + CRLF + CRLF);
    }
  }

  private String readRequestBody(BufferedReader in, int contentLength) throws IOException {
    char[] body = new char[contentLength];
    int read = in.read(body, 0, contentLength);
    if (read != contentLength) {
      throw new IOException("Failed to read complete request body");
    }
    return new String(body);
  }

  private void writeToFile(String fileName, String content) {
    File file = Paths.get(Main.basePath, fileName).toFile();
    try (FileWriter writer = new FileWriter(file)) {
      writer.write(content);
      writer.flush();
    } catch (IOException e) {
    }
  }

  private void handleGetRequest(OutputStream out, String urlPath, String userAgent, boolean connectionClose, Set<String> acceptEncoding) throws IOException {
    String responseHeaders = HTTP_OK + CRLF;
    if (connectionClose) {
      responseHeaders += "Connection: close" + CRLF;
    }

    String encoding = null;
    for (String enc : Main.supportedEncodings) {
      if (acceptEncoding.contains(enc)) {
        encoding = enc;
        responseHeaders += "Content-Encoding: " + encoding + CRLF;
        break;
      }
    }

    if (urlPath.startsWith("/echo/")) {
      String message = urlPath.substring("/echo/".length());
      responseHeaders += "Content-Type: text/plain" + CRLF;

      if ("gzip".equals(encoding)) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(byteArrayOutputStream)) {
          gzipOutputStream.write(message.getBytes());
        }
        byte[] compressedData = byteArrayOutputStream.toByteArray();

        responseHeaders += "Content-Length: " + compressedData.length + CRLF + CRLF;
        out.write(responseHeaders.getBytes());
        out.write(compressedData);
      } else {
        // Send uncompressed response
        responseHeaders += "Content-Length: " + message.length() + CRLF + CRLF + message;
        sendResponse(out, responseHeaders);
      }
    } else if (urlPath.equals("/user-agent")) {
      String response = responseHeaders +
              "Content-Type: text/plain" + CRLF +
              "Content-Length: " + userAgent.length() + CRLF +
              CRLF +
              userAgent;
      sendResponse(out, response);
    } else if (urlPath.equals("/")) {
      String response = responseHeaders + CRLF;
      sendResponse(out, response);
    } else if (urlPath.startsWith("/files/")) {
      handleFileRequest(out, urlPath, responseHeaders);
    } else {
      sendResponse(out, HTTP_NOT_FOUND + CRLF + CRLF);
    }
  }

  private void handleFileRequest(OutputStream out, String urlPath, String initialHeaders) throws IOException {
    String fileName = urlPath.substring("/files/".length());
    File file = new File(Main.basePath, fileName);

    if (!file.exists() || file.isDirectory()) {
      sendResponse(out, HTTP_NOT_FOUND + CRLF + CRLF);
    } else {
      byte[] fileContent = Files.readAllBytes(file.toPath());
      String responseHeaders = initialHeaders +
              "Content-Type: application/octet-stream" + CRLF +
              "Content-Length: " + fileContent.length + CRLF +
              CRLF;
      out.write(responseHeaders.getBytes());
      out.write(fileContent);
    }
  }

  private void sendResponse(OutputStream out, String response) throws IOException {
    out.write(response.getBytes());
  }
}