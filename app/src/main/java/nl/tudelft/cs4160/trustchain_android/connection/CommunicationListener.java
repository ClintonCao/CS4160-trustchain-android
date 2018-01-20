package nl.tudelft.cs4160.trustchain_android.connection;


import nl.tudelft.cs4160.trustchain_android.Peer;
import nl.tudelft.cs4160.trustchain_android.message.MessageProto;

/**
 * A listener, which can be used to report what the status is of send/received messages.
 */
public interface CommunicationListener {

    void updateLog(String msg);
    void requestPermission(MessageProto.TrustChainBlock block);
    void connectionSuccessful(byte[] publicKey);
}
