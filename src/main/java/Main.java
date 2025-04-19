import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class Main {
  public static void main(String[] args) {

    try {
      ServerSocket serverSocket = new ServerSocket(4221);
      serverSocket.setReuseAddress(true);
      Socket socket = serverSocket.accept();
      BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
      OutputStream out = socket.getOutputStream();

      String requestLine = in.readLine();
      String urlPath = requestLine.split(" ")[1];
      if (urlPath.equals("/")) {
        out.write("HTTP/1.1 200 OK\r\n\r\n".getBytes());
      } else {
        out.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes())
      }

      System.out.println("accepted new connection");
      socket.close();
    } catch (IOException e) {
      System.out.println("IOException: " + e.getMessage());
    }
  }
}
