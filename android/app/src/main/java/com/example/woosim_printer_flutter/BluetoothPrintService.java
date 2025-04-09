package com.example.woosim_printer_flutter;

import com.example.woosim_printer_flutter.MethodChannelManager;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.UUID;

class BluetoothPrintService {
    private static final String TAG = "BluetoothPrintService";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Handler mHandler;
    private int mState;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;

    // Constants that indicate the current connection state
    static final int STATE_NONE = 0;               // we're doing nothing
    private static final int STATE_LISTEN = 1;     // now listening for incoming connections
    private static final int STATE_CONNECTING = 2; // now initiating an outgoing connection
    static final int STATE_CONNECTED = 3;          // now connected to a remote device

    /**
     * Constructor. Prepares a new Bluetooth session.
     * @param handler  A Handler to send messages back to the UI Activity
     */
    BluetoothPrintService(Handler handler) {
        mState = STATE_NONE;
        mHandler = handler;
    }

    private synchronized void setState(int state) {
        mState = state;
    }

    synchronized int getState() {
        return mState;
    }

    public synchronized void start() {
        // Cancel any thread attempting to make a connection
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }
        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }
        setState(STATE_LISTEN);
    }

    synchronized void stop() {
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }
        setState(STATE_NONE);
    }

    /**
     * Start the ConnectThread to initiate a connection to a remote device.
     * @param device  The BluetoothDevice to connect
     */
    synchronized void connect(BluetoothDevice device) {
        // Cancel any thread attempting to make a connection
        if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {
                mConnectThread.cancel();
                mConnectThread = null;
            }
        }
        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }
        // Start the thread to connect with the given device
        mConnectThread = new ConnectThread(device);
        mConnectThread.start();
        setState(STATE_CONNECTING);
    }

    /**
     * Start the ConnectedThread to begin managing a Bluetooth connection
     * @param socket  The BluetoothSocket on which the connection was made
     * @param deviceName  The name of the BluetoothDevice that has been connected
     */
    private synchronized void connected(BluetoothSocket socket, String deviceName) {
        // Cancel the thread that completed the connection
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }
        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }
        // Start the thread to manage the connection and perform transmissions
        mConnectedThread = new ConnectedThread(socket);
        mConnectedThread.start();
        // Send the name of the connected device back to the UI Activity
        mHandler.obtainMessage(MainActivity.MESSAGE_DEVICE_NAME, deviceName).sendToTarget();
        setState(STATE_CONNECTED);

        // Notify Flutter about successful connection
        MethodChannelManager.sendStatusToFlutter("CONNECTED", deviceName);
    }

    /**
     * Write to the ConnectedThread in an un-synchronized manner
     * @param out The bytes to write
     * @see ConnectedThread#write(byte[])
     */
    void write(byte[] out) {
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) {
                mHandler.obtainMessage(MainActivity.MESSAGE_TOAST, R.string.not_connected, 0).sendToTarget();
                return;
            }
            r = mConnectedThread;
        }
        // Perform the write un-synchronized
        r.write(out);
    }

    private void connectionFailed() {
        // When the application is destroyed, just return
        if (mState == STATE_NONE)
            return;
        // Send a failure message back to the Activity
        mHandler.obtainMessage(MainActivity.MESSAGE_TOAST, R.string.connect_fail, 0).sendToTarget();

        // Send update to Flutter
        MethodChannelManager.sendStatusToFlutter("FAILED");

        // Start the service over to restart listening mode
        this.start();
    }

    private void connectionLost() {
        // When the application is destroyed, just return
        if (mState == STATE_NONE)
            return;
        // Send a failure message back to the Activity
        mHandler.obtainMessage(MainActivity.MESSAGE_TOAST, R.string.connect_lost, 0).sendToTarget();

        // Send update to Flutter
        MethodChannelManager.sendStatusToFlutter("DISCONNECTED");

        // Start the service over to restart listening mode
        this.start();
    }

    /**
     * This thread runs while attempting to make an outgoing connection
     * with a device. It runs straight through; the connection either
     * succeeds or fails.
     */
    @SuppressLint("MissingPermission")
    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        ConnectThread(BluetoothDevice device) {
            mmDevice = device;
            BluetoothSocket socket = null;
            // Get a BluetoothSocket for a connection with the given BluetoothDevice
            try {
                socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
            } catch (IOException e) {
                Log.e(TAG, "createSocket() failed", e);
            }
            mmSocket = socket;
        }

        public void run() {
            // Make a connection to the BluetoothSocket
            try {
                // This is a blocking call and will only return on a successful connection or an exception
                mmSocket.connect();
            } catch (IOException e) {
                // Close the socket
                try {
                    mmSocket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "unable to close() socket during connection failure", e2);
                }
                Log.e(TAG, "Connection Failed", e);
                connectionFailed();
                return;
            }
            // Reset the ConnectThread because we're done
            synchronized (BluetoothPrintService.this) {
                mConnectThread = null;
            }
            // Start the connected thread
            connected(mmSocket, mmDevice.getName());
        }

        void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }

    /**
     * This thread runs during a connection with a remote device.
     * It handles all incoming and outgoing transmissions.
     */
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;
            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "temp sockets not created", e);
            }
            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            Log.i(TAG, "BEGIN mConnectedThread");
            byte[] buffer = new byte[1024];
            int bytes;

            // Keep listening to the InputStream while connected
            while (true) {
                try {
                    // Read from the InputStream
                    bytes = mmInStream.read(buffer);
                    // buffer can be over-written by next input stream data, so it should be copied
                    byte[] rcvData = Arrays.copyOf(buffer, bytes);
                    // Send the obtained bytes to the UI Activity
                    mHandler.obtainMessage(MainActivity.MESSAGE_READ, bytes, -1, rcvData).sendToTarget();
                } catch (IOException e) {
                    Log.e(TAG, "Connection Lost", e);
                    connectionLost();
                    break;
                }
            }
        }

        /**
         * Write to the connected OutStream.
         * @param buffer  The bytes to write
         */
        void write(byte[] buffer) {
            try {
                mmOutStream.write(buffer);
            } catch (IOException e) {
                Log.e(TAG, "Exception during write", e);
                connectionLost();
            }
        }

        void cancel() {
            try {
                mmInStream.close();
                mmOutStream.close();
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }
}
