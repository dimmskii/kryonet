/* Copyright (c) 2008, Nathan Sweet
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 * 
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. */

package com.esotericsoftware.kryonet;

import static com.esotericsoftware.minlog.Log.DEBUG;
import static com.esotericsoftware.minlog.Log.ERROR;
import static com.esotericsoftware.minlog.Log.INFO;
import static com.esotericsoftware.minlog.Log.TRACE;
import static com.esotericsoftware.minlog.Log.debug;
import static com.esotericsoftware.minlog.Log.error;
import static com.esotericsoftware.minlog.Log.info;
import static com.esotericsoftware.minlog.Log.trace;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.SocketChannel;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryonet.FrameworkMessage.Ping;
import com.esotericsoftware.kryonet.serialization.Serialization;

// BOZO - Layer to handle handshake state.

/**
 * Represents a TCP and optionally a UDP connection between a {@link Client} and
 * a {@link Server}. If either underlying connection is closed or errors, both
 * connections are closed.
 *
 * @author Nathan Sweet &lt;misc@n4te.com&gt;
 */
public class Connection {
	int id = -1;
	private String name;
	EndPoint endPoint;
	TcpConnection tcp;
	UdpConnection udp;
	InetSocketAddress udpRemoteAddress;
	private Listener[] listeners = {};
	private final Object listenerLock = new Object();
	private int lastPingID;
	private long lastPingSendTime;
	private int returnTripTime;
	volatile boolean isConnected;
	volatile KryoNetException lastProtocolError;
	private Object arbitraryData;

	protected Connection() {
	}

	void initialize(Serialization serialization, int writeBufferSize,
			int objectBufferSize) {
		tcp = new TcpConnection(serialization, writeBufferSize,
				objectBufferSize);
	}

	/**
	 * Returns the server assigned ID. Will return <code>-1</code> if this
	 * connection has never been connected or the last assigned ID if this
	 * connection has been disconnected.
	 */
	public int getID() {
		return id;
	}

	/**
	 * Returns true if this connection is connected to the remote end. Note that
	 * a connection can become disconnected at any time.
	 */
	public boolean isConnected() {
		return isConnected;
	}

	/**
	 * Returns the last protocol error that occurred on the connection.
	 *
	 * @return The last protocol error or null if none error occurred.
	 */
	public KryoNetException getLastProtocolError() {
		return lastProtocolError;
	}

	/**
	 * Sends the object over the network using TCP.
	 *
	 * @return The number of bytes sent.
	 * @see Kryo#register(Class, com.esotericsoftware.kryo.Serializer)
	 */
	public int sendTCP(Object object) {
		if (object == null)
			throw new NullPointerException("object to send cannot be null.");
		try {
			int length = tcp.send(this, object);
			if (length == 0) {
				if (TRACE)
					trace("kryonet", this + " TCP had nothing to send.");
			} else if (DEBUG) {
				String objectString = object == null ? "null"
						: object.getClass().getSimpleName();
				if (!(object instanceof FrameworkMessage)) {
					debug("kryonet", this + " sent TCP: " + objectString + " ("
							+ length + ")");
				} else if (TRACE) {
					trace("kryonet", this + " sent TCP: " + objectString + " ("
							+ length + ")");
				}
			}
			return length;
		} catch (IOException ex) {
			if (DEBUG)
				debug("kryonet", "Unable to send TCP with connection: " + this,
						ex);
			close();
			return 0;
		} catch (KryoNetException ex) {
			if (ERROR)
				error("kryonet", "Unable to send TCP with connection: " + this,
						ex);
			close();
			return 0;
		}
	}

	/**
	 * Sends the object over the network using UDP.
	 *
	 * @return The number of bytes sent.
	 * @throws IllegalStateException
	 *             if this connection was not opened with both TCP and UDP.
	 * @see Kryo#register(Class, com.esotericsoftware.kryo.Serializer)
	 */
	public int sendUDP(Object object) {
		if (object == null)
			throw new NullPointerException("object to send cannot be null.");
		SocketAddress address = udpRemoteAddress;
		if (address == null && udp != null)
			address = udp.connectedAddress;
		if (address == null && isConnected)
			throw new IllegalStateException(
					"This connection is not connected via UDP.");

		try {
			if (address == null)
				throw new SocketException("Connection is closed.");

			int length = udp.send(this, object, address);
			if (length == 0) {
				if (TRACE)
					trace("kryonet", this + " UDP had nothing to send.");
			} else if (DEBUG) {
				if (length != -1) {
					String objectString = object == null ? "null"
							: object.getClass().getSimpleName();
					if (!(object instanceof FrameworkMessage)) {
						debug("kryonet", this + " sent UDP: " + objectString
								+ " (" + length + ")");
					} else if (TRACE) {
						trace("kryonet", this + " sent UDP: " + objectString
								+ " (" + length + ")");
					}
				} else
					debug("kryonet", this
							+ " was unable to send, UDP socket buffer full.");
			}
			return length;
		} catch (IOException ex) {
			if (DEBUG)
				debug("kryonet", "Unable to send UDP with connection: " + this,
						ex);
			close();
			return 0;
		} catch (KryoNetException ex) {
			if (ERROR)
				error("kryonet", "Unable to send UDP with connection: " + this,
						ex);
			close();
			return 0;
		}
	}

	public void close() {
		boolean wasConnected = isConnected;
		isConnected = false;
		tcp.close();
		if (udp != null && udp.connectedAddress != null)
			udp.close();
		if (wasConnected) {
			notifyDisconnected();
			if (INFO)
				info("kryonet", this + " disconnected.");
		}
		setConnected(false);
	}

	/**
	 * Requests the connection to communicate with the remote computer to
	 * determine a new value for the {@link #getReturnTripTime() return trip
	 * time}. When the connection receives a {@link FrameworkMessage.Ping}
	 * object with {@link Ping#isReply isReply} set to true, the new return trip
	 * time is available.
	 */
	public void updateReturnTripTime() {
		Ping ping = new Ping();
		ping.id = lastPingID++;
		lastPingSendTime = System.currentTimeMillis();
		sendTCP(ping);
	}

	/**
	 * Returns the last calculated TCP return trip time, or -1 if
	 * {@link #updateReturnTripTime()} has never been called or the
	 * {@link FrameworkMessage.Ping} response has not yet been received.
	 */
	public int getReturnTripTime() {
		return returnTripTime;
	}

	/**
	 * An empty object will be sent if the TCP connection has not sent an object
	 * within the specified milliseconds. Periodically sending a keep alive
	 * ensures that an abnormal close is detected in a reasonable amount of time
	 * (see {@link #setTimeout(int)} ). Also, some network hardware will close a
	 * TCP connection that ceases to transmit for a period of time (typically 1+
	 * minutes). Set to <code>0</code> to disable. Defaults to
	 * <code>8000</code>.
	 */
	public void setKeepAliveTCP(int keepAliveMillis) {
		tcp.keepAliveMillis = keepAliveMillis;
	}

	/**
	 * If the specified amount of time passes without receiving an object over
	 * TCP, the connection is considered closed. When a TCP socket is closed
	 * normally, the remote end is notified immediately and this timeout is not
	 * needed. However, if a socket is closed abnormally (e.g. power loss),
	 * KryoNet uses this timeout to detect the problem. The timeout should be
	 * set higher than the {@link #setKeepAliveTCP(int) TCP keep alive} for the
	 * remote end of the connection. The keep alive ensures that the remote end
	 * of the connection will be constantly sending objects, and setting the
	 * timeout higher than the keep alive allows for network latency. Set to
	 * <code>0</code> to disable. Defaults to <code>12000</code>.
	 */
	public void setTimeout(int timeoutMillis) {
		tcp.timeoutMillis = timeoutMillis;
	}

	/**
	 * Adds a listener to the connection. If the given listener was already
	 * added before, it is ignored.
	 * 
	 * @param listener
	 *            The listener to add.
	 */
	public void addListener(Listener listener) {
		if (listener == null)
			throw new NullPointerException("listener cannot be null.");
		synchronized (listenerLock) {
			Listener[] listeners = this.listeners;
			int n = listeners.length;
			for (int i = 0; i < n; i++)
				if (listener == listeners[i])
					return;
			Listener[] newListeners = new Listener[n + 1];
			newListeners[0] = listener;
			System.arraycopy(listeners, 0, newListeners, 1, n);
			this.listeners = newListeners;
		}
		if (TRACE)
			trace("kryonet", "Connection listener added: "
					+ listener.getClass().getName());
	}

	public void removeListener(Listener listener) {
		if (listener == null)
			throw new NullPointerException("listener cannot be null.");
		synchronized (listenerLock) {
			Listener[] listeners = this.listeners;
			int n = listeners.length;
			if (n == 0)
				return;
			Listener[] newListeners = new Listener[n - 1];
			for (int i = 0, ii = 0; i < n; i++) {
				Listener copyListener = listeners[i];
				if (listener == copyListener)
					continue;
				if (ii == n - 1)
					return;
				newListeners[ii++] = copyListener;
			}
			this.listeners = newListeners;
		}
		if (TRACE)
			trace("kryonet", "Connection listener removed: "
					+ listener.getClass().getName());
	}

	void notifyConnected() {
		if (INFO) {
			SocketChannel socketChannel = tcp.socketChannel;
			if (socketChannel != null) {
				Socket socket = tcp.socketChannel.socket();
				if (socket != null) {
					InetSocketAddress remoteSocketAddress = (InetSocketAddress) socket
							.getRemoteSocketAddress();
					if (remoteSocketAddress != null)
						info("kryonet", this + " connected: "
								+ remoteSocketAddress.getAddress());
				}
			}
		}
		Listener[] listeners = this.listeners;
		for (int i = 0, n = listeners.length; i < n; i++)
			listeners[i].connected(this);
	}

	void notifyDisconnected() {
		Listener[] listeners = this.listeners;
		for (int i = 0, n = listeners.length; i < n; i++)
			listeners[i].disconnected(this);
	}

	void notifyIdle() {
		Listener[] listeners = this.listeners;
		for (int i = 0, n = listeners.length; i < n; i++) {
			listeners[i].idle(this);
			if (!isIdle())
				break;
		}
	}

	void notifyReceived(Object object) {
		if (object instanceof Ping) {
			Ping ping = (Ping) object;
			if (ping.isReply) {
				if (ping.id == lastPingID - 1) {
					returnTripTime = (int) (System.currentTimeMillis()
							- lastPingSendTime);
					if (TRACE)
						trace("kryonet",
								this + " return trip time: " + returnTripTime);
				}
			} else {
				ping.isReply = true;
				sendTCP(ping);
			}
		}
		Listener[] listeners = this.listeners;
		for (int i = 0, n = listeners.length; i < n; i++)
			listeners[i].received(this, object);
	}

	/**
	 * Returns the local {@link Client} or {@link Server} to which this
	 * connection belongs.
	 */
	public EndPoint getEndPoint() {
		return endPoint;
	}

	/**
	 * Returns the IP address and port of the remote end of the TCP connection,
	 * or null if this connection is not connected.
	 */
	public InetSocketAddress getRemoteAddressTCP() {
		SocketChannel socketChannel = tcp.socketChannel;
		if (socketChannel != null) {
			Socket socket = tcp.socketChannel.socket();
			if (socket != null) {
				return (InetSocketAddress) socket.getRemoteSocketAddress();
			}
		}
		return null;
	}

	/**
	 * Returns the IP address and port of the remote end of the UDP connection,
	 * or <code>null</code> if this connection is not connected.
	 */
	public InetSocketAddress getRemoteAddressUDP() {
		InetSocketAddress connectedAddress = udp.connectedAddress;
		if (connectedAddress != null)
			return connectedAddress;
		return udpRemoteAddress;
	}

	/**
	 * Workaround for broken NIO networking on Android 1.6. If true, the
	 * underlying NIO buffer is always copied to the beginning of the buffer
	 * before being given to the SocketChannel for sending. The Harmony
	 * SocketChannel implementation in Android 1.6 ignores the buffer position,
	 * always copying from the beginning of the buffer. This is fixed in Android
	 * 2.0+.
	 */
	public void setBufferPositionFix(boolean bufferPositionFix) {
		tcp.bufferPositionFix = bufferPositionFix;
	}

	/**
	 * Sets the friendly name of this connection. This is returned by
	 * {@link #toString()} and is useful for providing application specific
	 * identifying information in the logging. Can be <code>null</code>.
	 * 
	 * @see #toString()
	 */
	public void setName(String name) {
		this.name = name;
	}

	/**
	 * Returns the number of bytes that are waiting to be written to the TCP
	 * socket, if any.
	 */
	public int getTcpWriteBufferSize() {
		return tcp.writeBuffer.position();
	}

	/**
	 * @see #setIdleThreshold(float)
	 */
	public boolean isIdle() {
		return tcp.writeBuffer.position()
				/ (float) tcp.writeBuffer.capacity() < tcp.idleThreshold;
	}

	/**
	 * If the percent of the TCP write buffer that is filled is less than the
	 * specified threshold, {@link Listener#idle(Connection)} will be called for
	 * each network thread update. Default is <code>0.1</code>.
	 */
	public void setIdleThreshold(float idleThreshold) {
		tcp.idleThreshold = idleThreshold;
	}

	/**
	 * The {@linkplain #name user-friendly} name of this connection or
	 * <code>Connection X</code>, where <code>X</code> is the
	 * {@linkplain #getID() connection ID}, per default.
	 */
	@Override
	public String toString() {
		if (name != null)
			return name;
		return "Connection " + id;
	}

	void setConnected(boolean isConnected) {
		this.isConnected = isConnected;
		if (isConnected && name == null)
			name = "Connection " + id;
	}

	public Object getArbitraryData() {
		return arbitraryData;
	}

	public void setArbitraryData(Object arbitraryData) {
		this.arbitraryData = arbitraryData;
	}

	@Override
	public int hashCode() {
		return 31 + id;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Connection other = (Connection) obj;
		return id == other.id;
	}
}
