import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class Main {
  public static void main(String[] args) {
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
