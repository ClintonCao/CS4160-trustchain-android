package nl.tudelft.cs4160.trustchain_android.main;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.support.design.widget.CoordinatorLayout;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import nl.tudelft.cs4160.trustchain_android.Network.Network;
import nl.tudelft.cs4160.trustchain_android.Network.NetworkCommunicationListener;
import nl.tudelft.cs4160.trustchain_android.Peer;
import nl.tudelft.cs4160.trustchain_android.R;
import nl.tudelft.cs4160.trustchain_android.SharedPreferences.BootstrapIPStorage;
import nl.tudelft.cs4160.trustchain_android.SharedPreferences.InboxItemStorage;
import nl.tudelft.cs4160.trustchain_android.SharedPreferences.PubKeyAndAddressPairStorage;
import nl.tudelft.cs4160.trustchain_android.SharedPreferences.UserNameStorage;
import nl.tudelft.cs4160.trustchain_android.Util.ByteArrayConverter;
import nl.tudelft.cs4160.trustchain_android.Util.Key;
import nl.tudelft.cs4160.trustchain_android.appToApp.PeerAppToApp;
import nl.tudelft.cs4160.trustchain_android.appToApp.PeerHandler;
import nl.tudelft.cs4160.trustchain_android.appToApp.connection.PeerListener;
import nl.tudelft.cs4160.trustchain_android.appToApp.connection.messages.BlockMessage;
import nl.tudelft.cs4160.trustchain_android.appToApp.connection.messages.IntroductionRequest;
import nl.tudelft.cs4160.trustchain_android.appToApp.connection.messages.IntroductionResponse;
import nl.tudelft.cs4160.trustchain_android.appToApp.connection.messages.Message;
import nl.tudelft.cs4160.trustchain_android.appToApp.connection.messages.MessageException;
import nl.tudelft.cs4160.trustchain_android.appToApp.connection.messages.Puncture;
import nl.tudelft.cs4160.trustchain_android.appToApp.connection.messages.PunctureRequest;
import nl.tudelft.cs4160.trustchain_android.block.TrustChainBlock;
import nl.tudelft.cs4160.trustchain_android.chainExplorer.ChainExplorerActivity;
import nl.tudelft.cs4160.trustchain_android.connection.CommunicationSingleton;
import nl.tudelft.cs4160.trustchain_android.database.TrustChainDBHelper;
import nl.tudelft.cs4160.trustchain_android.inbox.InboxActivity;
import nl.tudelft.cs4160.trustchain_android.inbox.InboxItem;
import nl.tudelft.cs4160.trustchain_android.message.MessageProto;

import static nl.tudelft.cs4160.trustchain_android.block.TrustChainBlock.GENESIS_SEQ;

public class OverviewConnectionsActivity extends AppCompatActivity implements NetworkCommunicationListener, PeerListener {

    public static String CONNECTABLE_ADDRESS = "130.161.211.254";
    public final static int DEFAULT_PORT = 1873;
    private static final int BUFFER_SIZE = 65536;
    private PeerListAdapter incomingPeerAdapter;
    private PeerListAdapter outgoingPeerAdapter;
    private TrustChainDBHelper dbHelper;
    private Network network;
    private PeerHandler peerHandler;

    /**
     * Initialize views, start send and receive threads if necessary.
     *
     * @param savedInstanceState saved instance state
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_overview);
        initVariables(savedInstanceState);
        initExitButton();
        addInitialPeer();
        startListenThread();
        startSendThread();
        initPeerLists();
        if (savedInstanceState != null) {
            updatePeerLists();
        }
        CommunicationSingleton.initContextAndListener(getApplicationContext(), null);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        return true;
    }

    /**
     * Define what should be executed when one of the item in the menu is clicked.
     *
     * @param item the item in the menu.
     * @return true if everything was executed.
     */
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.chain_menu:
                Intent chainExplorerActivity = new Intent(this, ChainExplorerActivity.class);
                startActivity(chainExplorerActivity);
                return true;
            case R.id.connection_explanation_menu:
                Intent ConnectionExplanationActivity = new Intent(this, ConnectionExplanationActivity.class);
                startActivity(ConnectionExplanationActivity);
                return true;
            case R.id.find_peer:
                Intent bootstrapActivity = new Intent(this, BootstrapActivity.class);
                startActivityForResult(bootstrapActivity, 1);
            default:
                return true;
        }
    }

    public void onClickOpenInbox(View view) {
        InboxActivity.peerList = peerHandler.getPeerList();
        Intent inboxActivityIntent = new Intent(this, InboxActivity.class);
        startActivity(inboxActivityIntent);
    }

    private void initKey() {
        KeyPair kp = Key.loadKeys(getApplicationContext());
        if (kp == null) {
            kp = Key.createAndSaveKeys(getApplicationContext());
        }
        if (isStartedFirstTime(dbHelper, kp)) {
            MessageProto.TrustChainBlock block = TrustChainBlock.createGenesisBlock(kp);
            dbHelper.insertInDB(block);
        }
    }

    /**
     * Checks if this is the first time the app is started and returns a boolean value indicating
     * this state.
     *
     * @return state - false if the app has been initialized before, true if first time app started
     */
    public boolean isStartedFirstTime(TrustChainDBHelper dbHelper, KeyPair kp) {
        // check if a genesis block is present in database
        MessageProto.TrustChainBlock genesisBlock = dbHelper.getBlock(kp.getPublic().getEncoded(), GENESIS_SEQ);
        return (genesisBlock == null);
    }

    private void initVariables(Bundle savedInstanceState) {
        peerHandler = new PeerHandler(UserNameStorage.getUserName(this));
        dbHelper = new TrustChainDBHelper(this);
        initKey();
        network = Network.getInstance(getApplicationContext());

        if (savedInstanceState != null) {
            ArrayList<PeerAppToApp> list = (ArrayList<PeerAppToApp>) savedInstanceState.getSerializable("peers");
            setPeersFromSavedInstance(list);
        }

        setPeerListener(this);
        network.setNetworkCommunicationListener(this);
        network.updateConnectionType((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE));
        ((TextView) findViewById(R.id.peer_id)).setText(peerHandler.getHashId());
    }

    public void setPeersFromSavedInstance(ArrayList<PeerAppToApp> peers) {
        getPeerHandler().setPeerList(peers);
    }

    public void setPeerListener(PeerListener peerListener) {
        getPeerHandler().setPeerListener(peerListener);
    }

    /**
     * Initialize the exit button.
     */
    private void initExitButton() {
        Button mExitButton = (Button) findViewById(R.id.exit_button);
        mExitButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
    }

    /**
     * Initialize the inboxItem lists.
     */
    private void initPeerLists() {
        ListView incomingPeerConnectionListView = (ListView) findViewById(R.id.incoming_peer_connection_list_view);
        ListView outgoingPeerConnectionListView = (ListView) findViewById(R.id.outgoing_peer_connection_list_view);
        incomingPeerAdapter = new PeerListAdapter(getApplicationContext(), R.layout.peer_connection_list_item, peerHandler.getIncomingList(), PeerAppToApp.INCOMING, (CoordinatorLayout) findViewById(R.id.myCoordinatorLayout));
        incomingPeerConnectionListView.setAdapter(incomingPeerAdapter);
        outgoingPeerAdapter = new PeerListAdapter(getApplicationContext(), R.layout.peer_connection_list_item, peerHandler.getOutgoingList(), PeerAppToApp.OUTGOING, (CoordinatorLayout) findViewById(R.id.myCoordinatorLayout));
        outgoingPeerConnectionListView.setAdapter(outgoingPeerAdapter);
    }


    /**
     * This method is the callback when submitting the ip address.
     * The method is called when leaving the BootstrapActivity.
     * The filled in ip address is passed on to this method.
     * When the callback of the bootstrap activity is successful
     * set this ip address as ConnectableAddress in the preferences.
     *
     * @param requestCode
     * @param resultCode
     * @param data        the data passed on by the previous activity, in this case the ip address
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 1) {
            if (resultCode == Activity.RESULT_OK) {
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
                SharedPreferences.Editor editor = preferences.edit();
                editor.putString("ConnectableAddress", data.getStringExtra("ConnectableAddress"));
                editor.apply();
                addInitialPeer();
            }
        }
    }

    /**
     * Add the intial hard-coded connectable inboxItem to the inboxItem list.
     */
    public void addInitialPeer() {
        String address = BootstrapIPStorage.getIP(this);
        CreateInetSocketAddressTask createInetSocketAddressTask = new CreateInetSocketAddressTask(this);
        try {
            if (address != null && !address.equals("")) {
                createInetSocketAddressTask.execute(address, String.valueOf(DEFAULT_PORT));
            } else {
                createInetSocketAddressTask.execute(CONNECTABLE_ADDRESS, String.valueOf(DEFAULT_PORT));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Asynctask to create the inetsocketaddress since network stuff can no longer happen on the main thread in android v3 (honeycomb).
     */
    private static class CreateInetSocketAddressTask extends AsyncTask<String, Void, InetSocketAddress> {
        private WeakReference<OverviewConnectionsActivity> activityReference;

        CreateInetSocketAddressTask(OverviewConnectionsActivity context) {
            activityReference = new WeakReference<>(context);
        }

        @Override
        protected InetSocketAddress doInBackground(String... params) {
            InetSocketAddress inetSocketAddress = null;
            OverviewConnectionsActivity activity = activityReference.get();
            if (activity == null) return null;

            try {
                InetAddress connectableAddress = InetAddress.getByName(params[0]);
                int port = Integer.parseInt(params[1]);
                inetSocketAddress = new InetSocketAddress(connectableAddress, port);

                activity.peerHandler.addPeer(null, inetSocketAddress, PeerAppToApp.OUTGOING);
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }

            return inetSocketAddress;
        }
    }


    /**
     * Start the thread send thread responsible for sending a {@link IntroductionRequest} to a random inboxItem every 5 seconds.
     */
    private void startSendThread() {
        Thread sendThread = new Thread(new Runnable() {
            @Override
            public void run() {
                do {
                    try {
                        if (peerHandler.size() > 0) {
                            PeerAppToApp peer = peerHandler.getEligiblePeer(null);
                            if (peer != null) {
                                network.sendIntroductionRequest(peer);
                                //  sendBlockMessage(peer);
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        break;
                    }
                } while (!Thread.interrupted());
                Log.d("App-To-App Log", "Send thread stopped");
            }
        });
        sendThread.start();
        Log.d("App-To-App Log", "Send thread started");
    }


    /**
     * Start the listen thread. The thread opens a new {@link DatagramChannel} and calls {@link Network#dataReceived(Context, ByteBuffer,
     * InetSocketAddress)} for each incoming datagram.
     */
    private void startListenThread() {
        final Context context = this;

        Thread listenThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ByteBuffer inputBuffer = ByteBuffer.allocate(BUFFER_SIZE);
                    while (!Thread.interrupted()) {
                        inputBuffer.clear();
                        SocketAddress address = network.receive(inputBuffer);
                        inputBuffer.flip();
                        network.dataReceived(context, inputBuffer, (InetSocketAddress) address);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    Log.d("App-To-App Log", "Listen thread stopped");
                }
            }
        });
        listenThread.start();
        Log.d("App-To-App Log", "Listen thread started");
    }

    /**
     * Set the external ip field based on the WAN vote.
     *
     * @param ip the ip address.
     */
    private void setWanvote(final String ip) {
        new Handler(getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                TextView mWanVote = (TextView) findViewById(R.id.wanvote);
                mWanVote.setText(ip);
            }
        });
    }

    /**
     * Handle an introduction request. Send a puncture request to the included invitee.
     *
     * @param peer    the origin inboxItem.
     * @param message the message.
     * @throws IOException
     */
    @Override
    public void handleIntroductionRequest(PeerAppToApp peer, IntroductionRequest message) throws IOException {
        peer.setNetworkOperator(message.getNetworkOperator());
        peer.setConnectionType((int) message.getConnectionType());
        if (getPeerHandler().size() > 1) {
            PeerAppToApp invitee = getPeerHandler().getEligiblePeer(peer);
            if (invitee != null) {
                network.sendIntroductionResponse(peer, invitee);
                network.sendPunctureRequest(invitee, peer);
                Log.d("Network", "Introducing " + invitee.getAddress() + " to " + peer.getAddress());
            }
        } else {
            Log.d("Network", "Peerlist too small, can't handle introduction request");
            network.sendIntroductionResponse(peer, null);
        }
    }

    /**
     * Handle an introduction response. Parse incoming PEX peers.
     *
     * @param peer    the origin inboxItem.
     * @param message the message.
     */
    @Override
    public void handleIntroductionResponse(PeerAppToApp peer, IntroductionResponse message) {
        peer.setConnectionType((int) message.getConnectionType());
        peer.setNetworkOperator(message.getNetworkOperator());
        List<PeerAppToApp> pex = message.getPex();
        for (PeerAppToApp pexPeer : pex) {
            if (getPeerHandler().hashId.equals(pexPeer.getPeerId())) continue;
            getPeerHandler().getOrMakePeer(pexPeer.getPeerId(), pexPeer.getAddress(), PeerAppToApp.OUTGOING);
        }
    }

    /**
     * Handle a puncture. Does nothing because the only purpose of a puncture is to punch a hole in the NAT.
     *
     * @param peer    the origin inboxItem.
     * @param message the message.
     * @throws IOException
     */
    @Override
    public void handlePuncture(PeerAppToApp peer, Puncture message) throws IOException {
    }

    /**
     * Handle a puncture request. Sends a puncture to the puncture inboxItem included in the message.
     *
     * @param peer    the origin inboxItem.
     * @param message the message.
     * @throws IOException
     * @throws MessageException
     */
    @Override
    public void handlePunctureRequest(PeerAppToApp peer, PunctureRequest message) throws IOException, MessageException {
        if (!getPeerHandler().peerExistsInList(message.getPuncturePeer())) {
            network.sendPuncture(message.getPuncturePeer());
        }
    }

    @Override
    public void handleBlockMessageRequest(PeerAppToApp peer, BlockMessage message) throws IOException, MessageException {
        MessageProto.Message msg = message.getMessageProto();
        if (msg.getCrawlRequest().getPublicKey().size() == 0) {
            MessageProto.TrustChainBlock block = msg.getHalfBlock();
            //add peer to inbox if needed
            InboxItem i = new InboxItem(peer.getPeerId(), new ArrayList<Integer>(), peer.getAddress().getHostString(), ByteArrayConverter.byteStringToString(block.getPublicKey()), peer.getPort());
            InboxItemStorage.addInboxItem(this, i);
            InboxItemStorage.addHalfBlock(CommunicationSingleton.getContext(), ByteArrayConverter.byteStringToString(block.getPublicKey()), block.getLinkSequenceNumber());
            CommunicationSingleton.getDbHelper().insertInDB(block);
            Log.d("testTheStacks", block.toString());
        }
    }

    /**
     * Update the showed inboxItem lists.
     */
    @Override
    public void updatePeerLists() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                peerHandler.splitPeerList();
                incomingPeerAdapter.notifyDataSetChanged();
                outgoingPeerAdapter.notifyDataSetChanged();
            }
        });
    }

    @Override
    protected void onDestroy() {
        network.closeChannel();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable("peers", peerHandler.getPeerList());

        super.onSaveInstanceState(outState);
    }

    public void updateWan(Message message) throws MessageException {
        if (peerHandler.getWanVote().vote(message.getDestination())) {
            Log.d("App-To-App Log", "Address changed to " + peerHandler.getWanVote().getAddress());
            updateInternalSourceAddress(peerHandler.getWanVote().getAddress().toString());
        }
        setWanvote(peerHandler.getWanVote().getAddress().toString());
    }

    @Override
    public PeerAppToApp getOrMakePeer(String id, InetSocketAddress address, boolean incoming) {
        return peerHandler.getOrMakePeer(id, address, incoming);
    }

    /**
     * Display connectionType
     *
     * @param connectionType
     * @param typename
     * @param subtypename
     */
    @Override
    public void updateConnectionType(int connectionType, String typename, String subtypename) {
        String connectionTypeStr = typename + " " + subtypename;
        ((TextView) findViewById(R.id.connection_type)).setText(connectionTypeStr);
    }

    @Override
    public void updateLog(final String msg) {
        //just to be sure run it on the ui thread
        //this is not necessary when this function is called from a AsyncTask
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ((TextView) findViewById(R.id.status)).append(msg);
            }
        });
    }

    @Override
    public void connectionSuccessful(byte[] publicKey) {

    }

    @Override
    public void requestPermission(final MessageProto.TrustChainBlock block, final Peer peer) {

    }

    @Override
    public void updateInternalSourceAddress(final String address) {
        Log.d("App-To-App Log", "Local ip: " + address);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView localIp = (TextView) findViewById(R.id.local_ip_address_view);
                localIp.setText(address);
            }
        });
    }

    @Override
    public void updateIncomingPeers() {
        incomingPeerAdapter.notifyDataSetChanged();
    }

    @Override
    public void updateOutgoingPeers() {
        outgoingPeerAdapter.notifyDataSetChanged();
    }

    @Override
    public PeerHandler getPeerHandler() {
        return peerHandler;
    }
}
