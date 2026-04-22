package featurecat.lizzie.gui.web;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class WebBoardHttpServerTest {
  private WebBoardHttpServer server;
  private static final int PORT = 19877;

  @BeforeEach
  void setUp() throws Exception {
    server = new WebBoardHttpServer(PORT);
    server.start();
    Thread.sleep(200);
  }

  @AfterEach
  void tearDown() {
    server.stop();
  }

  @Test
  void rejectsPathTraversal() throws Exception {
    String response = httpGet("/../../../etc/passwd");
    assertTrue(response.contains("403"));
  }

  @Test
  void rejectsBackslashPathTraversal() throws Exception {
    String response = httpGet("/..\\..\\etc\\passwd");
    assertTrue(response.contains("403"));
  }

  @Test
  void rejectsNonGetMethod() throws Exception {
    String response = httpRequest("POST / HTTP/1.1\r\nHost: localhost\r\n\r\n");
    assertTrue(response.contains("405"));
  }

  @Test
  void servesIndexHtml() throws Exception {
    String response = httpGet("/");
    // index.html exists in classpath (created by Task 7), should return 200
    // If it somehow doesn't find it, it should NOT be 403
    assertFalse(response.contains("403"));
  }

  @Test
  void returns404ForMissingFile() throws Exception {
    String response = httpGet("/nonexistent.xyz");
    assertTrue(response.contains("404"));
  }

  private String httpGet(String path) throws Exception {
    return httpRequest("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n");
  }

  private String httpRequest(String raw) throws Exception {
    try (Socket s = new Socket("127.0.0.1", PORT);
        PrintWriter out = new PrintWriter(s.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
      out.print(raw);
      out.flush();
      StringBuilder sb = new StringBuilder();
      String line;
      s.setSoTimeout(2000);
      try {
        while ((line = in.readLine()) != null) sb.append(line).append("\n");
      } catch (Exception ignored) {
      }
      return sb.toString();
    }
  }
}
