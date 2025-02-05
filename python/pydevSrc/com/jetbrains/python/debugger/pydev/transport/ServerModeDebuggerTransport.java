package com.jetbrains.python.debugger.pydev.transport;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.jetbrains.python.debugger.pydev.ProtocolFrame;
import com.jetbrains.python.debugger.pydev.RemoteDebugger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * @author Alexander Koshevoy
 */
public class ServerModeDebuggerTransport extends BaseDebuggerTransport {
  private static final Logger LOG = Logger.getInstance(ServerModeDebuggerTransport.class);

  @NotNull private final ServerSocket myServerSocket;
  private volatile DebuggerReader myDebuggerReader;

  private volatile boolean myConnected = false;
  private volatile Socket mySocket;
  private int myConnectionTimeout;

  public ServerModeDebuggerTransport(RemoteDebugger debugger, @NotNull ServerSocket socket, int connectionTimeout) {
    super(debugger);
    myServerSocket = socket;
    myConnectionTimeout = connectionTimeout;
  }

  @Override
  public void waitForConnect() throws IOException {
    //noinspection SocketOpenedButNotSafelyClosed
    myServerSocket.setSoTimeout(myConnectionTimeout);

    synchronized (mySocketObject) {
      mySocket = myServerSocket.accept();
      myConnected = true;
    }
    try {
      synchronized (mySocketObject) {
        myDebuggerReader = new DebuggerReader(myDebugger, mySocket.getInputStream());
      }
    }
    catch (IOException e) {
      try {
        mySocket.close();
      }
      catch (IOException ignore) {
      }
      throw e;
    }

    // mySocket is closed in close() method on process termination
  }

  @Override
  public void close() {
    try {
      if (myDebuggerReader != null) {
        myDebuggerReader.stop();
      }
    }
    finally {
      if (!myServerSocket.isClosed()) {
        try {
          myServerSocket.close();
        }
        catch (IOException e) {
          LOG.warn("Error closing socket", e);
        }
      }
    }
  }

  @Override
  public boolean isConnected() {
    return myConnected && mySocket != null && !mySocket.isClosed();
  }

  @Override
  public void disconnect() {
    synchronized (mySocketObject) {
      myConnected = false;

      if (mySocket != null && !mySocket.isClosed()) {
        try {
          mySocket.close();
        }
        catch (IOException ignore) {
        }
      }
    }
  }

  @Override
  public void messageReceived(@NotNull ProtocolFrame frame) {
    // do nothing
  }

  @Override
  protected boolean sendMessageImpl(byte[] packed) throws IOException {
    synchronized (mySocketObject) {
      if (mySocket == null) {
        return false;
      }
      final OutputStream os = mySocket.getOutputStream();
      os.write(packed);
      os.flush();
      return true;
    }
  }

  public static class DebuggerReader extends BaseDebuggerReader {
    public DebuggerReader(@NotNull RemoteDebugger debugger, @NotNull InputStream stream) throws IOException {
      super(stream, CharsetToolkit.UTF8_CHARSET, debugger); //TODO: correct encoding?
      start(getClass().getName());
    }

    protected void onCommunicationError() {getDebugger().fireCommunicationError();}
  }
}
