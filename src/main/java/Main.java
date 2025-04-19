import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;

public class Main {
  static String basePath;
  public static void main(String[] args) {
    basePath = null;
    for (int i = 0; i < args.length - 1; i++) {
      if (args[i].equals("--directory")) {
        basePath = args[i + 1];
        break;
      }
    }
    ServerSocket serverSocket = null;
    try {
      serverSocket = new ServerSocket(4221);
      serverSocket.setReuseAddress(true);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
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
  private Socket socket;

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
      String urlPath = requestLine.split(" ")[1];

      if (urlPath.startsWith("/echo")) {
        String path = urlPath.split("/")[2];
        String response = "HTTP/1.1 200 OK"
                + "\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: " + path.length() + "\r\n"
                + "\r\n"
                + path;
        out.write(response.getBytes());
      } else if (urlPath.equals("/user-agent")) {
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
          if (line.startsWith("User-Agent:")) {
            String userAgentHeaderValue = line.split(":", 2)[1].trim();
            String response = "HTTP/1.1 200 OK"
                    + "\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: " + userAgentHeaderValue.length() + "\r\n"
                    + "\r\n"
                    + userAgentHeaderValue;
            out.write(response.getBytes());
            break;
          }
        }
      } else if (urlPath.equals("/")) {
        out.write("HTTP/1.1 200 OK\r\n\r\n".getBytes());
      } else if (urlPath.startsWith("/files/")) {
        String fileName = urlPath.substring("/files/".length());
        File file = new File(Main.basePath, fileName);

        if (!file.exists() || file.isDirectory()) {
          out.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
        } else {
          byte[] fileContent = Files.readAllBytes(file.toPath());

          String responseHeaders = "HTTP/1.1 200 OK\r\n" +
                  "Content-Type: application/octet-stream\r\n" +
                  "Content-Length: " + fileContent.length + "\r\n" +
                  "\r\n";

          out.write(responseHeaders.getBytes());
          out.write(fileContent);
        }
      } else {
        out.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
      }

      out.flush();  // Ensure that the output is flushed properly
      socket.close();
    } catch (IOException e) {
      System.out.println("IOException while handling connection: " + e.getMessage());
    }
  }
}
