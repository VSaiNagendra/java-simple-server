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
      String requestLine = in.readLine();
      String[] requestLineContents = requestLine.split(" ");
      String method = requestLineContents[0];
      String urlPath = requestLineContents[1];

      if (Objects.equals(method, "POST")) {
        handlePostRequest(in, out, urlPath);
      } else {
        handleGetRequest(in, out, urlPath);
      }

      out.flush();
      socket.close();
    } catch (IOException e) {
      System.out.println("IOException while handling connection: " + e.getMessage());
    }
  }

  private void handlePostRequest(BufferedReader in, OutputStream out, String urlPath) throws IOException {
    if (urlPath.startsWith("/files")) {
      String fileName = urlPath.replace("/files/", "");
      int contentLength = getContentLength(in);
      String requestBody = readRequestBody(in, contentLength);

      writeToFile(fileName, requestBody);
      out.write((HTTP_CREATED + CRLF + CRLF).getBytes());
    }
  }

  private int getContentLength(BufferedReader in) throws IOException {
    String line;
    int contentLength = 0;
    while ((line = in.readLine()) != null && !line.isEmpty()) {
      if (line.startsWith("Content-Length:")) {
        contentLength = Integer.parseInt(line.split(":")[1].trim());
      }
    }
    return contentLength;
  }

  private String readRequestBody(BufferedReader in, int contentLength) throws IOException {
    char[] body = new char[contentLength];
    int read = in.read(body, 0, contentLength);
    return new String(body, 0, read);
  }

  private void writeToFile(String fileName, String content) {
    File file = Paths.get(Main.basePath, fileName).toFile();
    try (FileWriter writer = new FileWriter(file)) {
      writer.write(content);
      writer.flush();
    } catch (IOException e) {
    }
  }

  private void handleGetRequest(BufferedReader in, OutputStream out, String urlPath) throws IOException {
    if (urlPath.startsWith("/echo")) {
      handleEchoRequest(out, urlPath);
    } else if (urlPath.equals("/user-agent")) {
      handleUserAgentRequest(in, out);
    } else if (urlPath.equals("/")) {
      sendResponse(out, HTTP_OK + CRLF + CRLF);
    } else if (urlPath.startsWith("/files/")) {
      handleFileRequest(out, urlPath);
    } else {
      sendResponse(out, HTTP_NOT_FOUND + CRLF + CRLF);
    }
  }

  private void handleEchoRequest(OutputStream out, String urlPath) throws IOException {
    String path = urlPath.split("/")[2];
    String response = HTTP_OK + CRLF +
            "Content-Type: text/plain" + CRLF +
            "Content-Length: " + path.length() + CRLF +
            CRLF +
            path;
    sendResponse(out, response);
  }

  private void handleUserAgentRequest(BufferedReader in, OutputStream out) throws IOException {
    String line;
    while ((line = in.readLine()) != null && !line.isEmpty()) {
      if (line.startsWith("User-Agent:")) {
        String userAgentValue = line.split(":", 2)[1].trim();
        String response = HTTP_OK + CRLF +
                "Content-Type: text/plain" + CRLF +
                "Content-Length: " + userAgentValue.length() + CRLF +
                CRLF +
                userAgentValue;
        sendResponse(out, response);
        break;
      }
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