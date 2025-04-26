import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Objects;

public class Main {
  static String basePath;

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
          }
        }

        if (Objects.equals(method, "POST")) {
          handlePostRequest(in, out, urlPath, contentLength);
        } else if (Objects.equals(method, "GET")) {
          handleGetRequest(out, urlPath, userAgent);
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

  private void handlePostRequest(BufferedReader in, OutputStream out, String urlPath, int contentLength) throws IOException {
    if (urlPath.startsWith("/files/")) {
      String fileName = urlPath.substring("/files/".length());
      String requestBody = readRequestBody(in, contentLength);

      writeToFile(fileName, requestBody);
      sendResponse(out, HTTP_CREATED + CRLF + CRLF);
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

  private void handleGetRequest(OutputStream out, String urlPath, String userAgent) throws IOException {
    if (urlPath.startsWith("/echo/")) {
      String message = urlPath.substring("/echo/".length());
      String response = HTTP_OK + CRLF +
              "Content-Type: text/plain" + CRLF +
              "Content-Length: " + message.length() + CRLF +
              CRLF +
              message;
      sendResponse(out, response);
    } else if (urlPath.equals("/user-agent")) {
      String response = HTTP_OK + CRLF +
              "Content-Type: text/plain" + CRLF +
              "Content-Length: " + userAgent.length() + CRLF +
              CRLF +
              userAgent;
      sendResponse(out, response);
    } else if (urlPath.equals("/")) {
      sendResponse(out, HTTP_OK + CRLF + CRLF);
    } else if (urlPath.startsWith("/files/")) {
      handleFileRequest(out, urlPath);
    } else {
      sendResponse(out, HTTP_NOT_FOUND + CRLF + CRLF);
    }
  }

  private void handleFileRequest(OutputStream out, String urlPath) throws IOException {
    String fileName = urlPath.substring("/files/".length());
    File file = new File(Main.basePath, fileName);

    if (!file.exists() || file.isDirectory()) {
      sendResponse(out, HTTP_NOT_FOUND + CRLF + CRLF);
    } else {
      byte[] fileContent = Files.readAllBytes(file.toPath());
      String responseHeaders = HTTP_OK + CRLF +
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