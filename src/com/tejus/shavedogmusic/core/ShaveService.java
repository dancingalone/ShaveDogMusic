package com.tejus.shavedogmusic.core;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.StringTokenizer;

import org.json.JSONException;
import org.json.JSONObject;

import com.tejus.shavedogmusic.core.Definitions;
import com.tejus.shavedogmusic.R;
import com.tejus.shavedogmusic.activity.PlayActivity;
import com.tejus.shavedogmusic.utils.Logger;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.provider.MediaStore;
import android.widget.Toast;

public class ShaveService extends Service {

    private final Handler messageHandler = new Handler() {
        @Override
        public void handleMessage( Message msg ) {
            String messageType = msg.getData().getString( "message_type" );
            String messageValue = msg.getData().getString( "message_value" );
            if ( messageType != null && messageValue != null ) {
                if ( messageType.equals( "show_toast" ) ) {
                    Logger.d( "ShaveService.handleMessage: gonna show toast as: " + messageValue );
                    Toast.makeText( ShaveService.this, messageValue, Toast.LENGTH_SHORT );
                }
            }

        }
    };

    private final IBinder mBinder = new ShaveBinder();
    private NotificationManager mNM;
    private int NOTIFICATION = R.string.shave_service_started;
    private DatagramSocket mSearchSocket, mGenericSocket, mTestSocket;;
    WifiManager wifi;
    WifiManager.WifiLock wifiLock;
    boolean wifiConnected;
    DhcpInfo dhcp;
    ShaveFinder mFinder;
    public static ArrayList<String> peerList = new ArrayList<String>();
    public static HashMap<String, String> peerMap = new HashMap<String, String>();
    private static HashMap<String, Integer> uploadPortMap = new HashMap<String, Integer>();
    private static HashMap<String, Integer> downloadPortMap = new HashMap<String, Integer>();
    public static ArrayList<Integer> alreadyAssignedPorts = new ArrayList<Integer>();
    public static HashMap<String, ArrayList<String>> peerMusicHistory = new HashMap<String, ArrayList<String>>();
    private static int peerIndex = 0;
    private static String songName;
    // each peer is assigned exactly ONE uploader:
    private static HashMap<String, Uploader> peerUploaderMap = new HashMap<String, Uploader>();

    LocalMusicManager localMusicManager;

    public class ShaveBinder extends Binder {
        public ShaveService getService() {
            return ShaveService.this;
        }
    }

    @Override
    public IBinder onBind( Intent arg0 ) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        Logger.d( "ShaveService created, biatch" );
        mNM = ( NotificationManager ) getSystemService( NOTIFICATION_SERVICE );
        showNotification();
        setUpNetworkStuff();
        localMusicManager = new LocalMusicManager( getContentResolver() );
        // setup our request broadcast server:
        new RequestListener().execute( mSearchSocket );
        // // this's our generic listener:
        new RequestListener().execute( mGenericSocket );
    }

    @Override
    public int onStartCommand( Intent intent, int flags, int startId ) {

        String action = ( intent != null ) ? intent.getAction() : null;
        Logger.d( "ShaveService.onStartCommand: received intent.action = " + action );
        if ( action != null ) {
            if ( action.equals( Definitions.WIFI_STATE_CHANGE ) ) {
                this.wifiChanged();
            }
        }

        return START_STICKY;
    }

    private void wifiChanged() {
        Intent intent = new Intent( Definitions.WIFI_STATE_CHANGE );
        Logger.d( "ShaveService.wifiChanged : sending broadcast.." );
        this.sendBroadcast( intent );
    }

    @Override
    public void onDestroy() {
        mNM.cancel( NOTIFICATION );
        Toast.makeText( this, R.string.shave_service_stopped, Toast.LENGTH_SHORT ).show();
        if ( wifiLock.isHeld() ) {
            wifiLock.release();
        }
    }

    private class RequestListener extends AsyncTask<DatagramSocket, DatagramPacket, Void> {
        @Override
        protected void onProgressUpdate( DatagramPacket... packet ) {
            dealWithReceivedPacketNew( packet[ 0 ] );
        }

        @Override
        protected Void doInBackground( DatagramSocket... requestSocket ) {
            byte[] buffer = new byte[ Definitions.COMMAND_BUFSIZE ];
            DatagramPacket packet;
            while ( true ) {
                try {
                    packet = new DatagramPacket( buffer, buffer.length );
                    Logger.d( "ShaveService.RequestListener listening on : " + requestSocket[ 0 ].getLocalPort() );
                    requestSocket[ 0 ].receive( packet );
                    Logger.d( "Stuff received by Server = " + new String( packet.getData() ) );
                    publishProgress( packet );

                } catch ( IOException e ) {
                    Logger.d( "Server: Receive timed out.." );
                }
            }
        }
    }

    private void showNotification() {
        CharSequence text = getText( R.string.shave_service_started );
        Notification notification = new Notification( R.drawable.iconshave, text, System.currentTimeMillis() );
        PendingIntent contentIntent = PendingIntent.getActivity( this, 0, new Intent( this, PlayActivity.class ), 0 );
        notification.setLatestEventInfo( this, getText( R.string.awesomeness ), text, contentIntent );
        mNM.notify( NOTIFICATION, notification );
    }

    void setUpNetworkStuff() {
        initNetworkStuff();
        try {
            // temp. sockets used only here, that's why the ridiculous names:
            DatagramSocket socket1 = new DatagramSocket( Definitions.SEARCH_SERVER_PORT );
            socket1.setBroadcast( true );
            socket1.setSoTimeout( Definitions.SOCKET_TIMEOUT );
            mSearchSocket = socket1;

            DatagramSocket socket2 = new DatagramSocket( Definitions.GENERIC_SERVER_PORT );
            socket2.setBroadcast( true );
            socket2.setSoTimeout( Definitions.SOCKET_TIMEOUT );
            mGenericSocket = socket2;

            DatagramSocket socket3 = new DatagramSocket( Definitions.TEST_SERVER_PORT );
            socket3.setBroadcast( true );
            socket3.setSoTimeout( Definitions.SOCKET_TIMEOUT );
            mTestSocket = socket3;

        } catch ( Exception e ) {
            e.printStackTrace();
        }
    }

    void initNetworkStuff() {
        wifi = ( WifiManager ) this.getSystemService( Context.WIFI_SERVICE );
        if ( !wifi.isWifiEnabled() ) {
            Toast.makeText( this, R.string.no_wifi, Toast.LENGTH_LONG ).show();
        }
        dhcp = wifi.getDhcpInfo();
        getOurIp();
        WifiManager wifiManager = ( WifiManager ) this.getSystemService( Context.WIFI_SERVICE );
        wifiLock = wifiManager.createWifiLock( WifiManager.WIFI_MODE_FULL, "MyWifiLock" );
        wifiLock.acquire();
    }

    private InetAddress getOurIp() {
        Definitions.IP_ADDRESS_INT = dhcp.ipAddress;
        int ourIp = Definitions.IP_ADDRESS_INT;
        byte[] quads = new byte[ 4 ];
        try {
            for ( int k = 0; k < 4; k++ ) {
                quads[ k ] = ( byte ) ( ( ourIp >> k * 8 ) & 0xFF );
            }

            Definitions.IP_ADDRESS_INETADDRESS = InetAddress.getByAddress( quads );
            return InetAddress.getByAddress( quads );
        } catch ( UnknownHostException e ) {
            e.printStackTrace();
        }
        return null;

    }

    // Searches our subnet & our parent - subnet
    private class ShaveFinder extends AsyncTask<Void, DatagramPacket, Void> {

        @Override
        protected void onProgressUpdate( DatagramPacket... packet ) {
            dealWithReceivedPacketNew( packet[ 0 ] );
        }

        @Override
        protected Void doInBackground( Void... shavealicious ) {
            String ourIp = getOurIp().getHostAddress();
            String subnet = ( String ) ourIp.subSequence( 0, ourIp.lastIndexOf( "." ) );
            String subnetId = ( String ) subnet.subSequence( subnet.lastIndexOf( "." ) + 1, subnet.length() );
            String parentSubnet = ( String ) ourIp.subSequence( 0, subnet.lastIndexOf( "." ) );
            // search our subnet first:
            for ( int i = 0; i < 256; i++ ) {
                try {
                    if ( isCancelled() ) {
                        break;
                    }
                    String destinationAddress = subnet + "." + String.valueOf( i );
                    
                    JSONObject searchObject = new JSONObject();
                    searchObject.put( Definitions.MSG_TYPE, Definitions.DISCOVER_PING );
                    searchObject.put( Definitions.SENDER_ADDRESS, getOurIp().toString().replace( "/", "" ) );
                    searchObject.put( Definitions.SENDER_UNAME, getOurUserName() );                    
                    
                    byte[] data = searchObject.toString().getBytes();
                    Logger.d( "sending DISCOVER to = " + destinationAddress );
                    DatagramPacket sendPacket = new DatagramPacket( data, data.length, InetAddress.getByName( destinationAddress ), Definitions.SEARCH_SERVER_PORT );                    
                    mTestSocket.send( sendPacket );
                } catch ( SocketException e ) {
                    Bundle bundle = new Bundle();
                    bundle.putString( "message_type", "show_toast" );
                    bundle.putString( "message_value", getResources().getString( R.string.no_wifi ) );
                    Message showToast = new Message();
                    showToast.setData( bundle );
                    messageHandler.sendMessage( showToast );

                } catch ( Exception e ) {
                    e.printStackTrace();
                }
            }

            // search other subnets under our parent's subnet:

            for ( int j = 0; j < 256; j++ ) {
                if ( j == ( Integer.parseInt( subnetId ) - 1 ) || j == ( Integer.parseInt( subnetId ) + 1 ) ) {
                    String parentAddress = parentSubnet + "." + String.valueOf( j );
                    for ( int i = 0; i < 256; i++ ) {
                        try {
                            if ( isCancelled() ) {
                                break;
                            }
                            String destinationAddress = parentAddress + "." + String.valueOf( i );                            

                            JSONObject searchObject = new JSONObject();
                            searchObject.put( Definitions.MSG_TYPE, Definitions.DISCOVER_PING );
                            searchObject.put( Definitions.SENDER_ADDRESS, getOurIp().toString().replace( "/", "" ) );
                            searchObject.put( Definitions.SENDER_UNAME, getOurUserName() );                            
                            
                            byte[] data = searchObject.toString().getBytes();
                            Logger.d( "sending DISCOVER to = " + destinationAddress );
                            DatagramPacket sendPacket = new DatagramPacket( data, data.length, InetAddress.getByName( destinationAddress ), Definitions.SEARCH_SERVER_PORT );                            
                            mTestSocket.send( sendPacket );
                        } catch ( SocketException e ) {
                            Bundle bundle = new Bundle();
                            bundle.putString( "message_type", "show_toast" );
                            bundle.putString( "message_value", getResources().getString( R.string.no_wifi ) );
                            Message showToast = new Message();
                            showToast.setData( bundle );
                            messageHandler.sendMessage( showToast );
                        }

                        catch ( Exception e ) {
                            e.printStackTrace();
                        }
                    }
                }
                if ( isCancelled() ) {
                    Logger.d( "TestSearchMethod: stopping search, since a friend replied.. " );
                    break;
                }
            }

            return null;
        }
    }

    private String getOurUserName() {
        SharedPreferences settings = getSharedPreferences( Definitions.credsPrefFile, Context.MODE_PRIVATE );
        return settings.getString( Definitions.prefUserName, Definitions.defaultUserName );
    }

    public void testPopulateList() {
        mFinder = new ShaveFinder();
        mFinder.execute();
    }

    // Request processor:
    // private void dealWithReceivedPacket( DatagramPacket packet ) {
    //
    // String words[] = new String[ Definitions.COMMAND_WORD_LENGTH + 1 ];
    // int wordCounter = 0;
    // String senderAddress, userName;
    // String command = new String( packet.getData() );
    // Logger.d( "command here = " + command );
    //
    // StringTokenizer strTok = new StringTokenizer( command,
    // Definitions.COMMAND_DELIM );
    // while ( strTok.hasMoreTokens() && wordCounter <=
    // Definitions.COMMAND_WORD_LENGTH ) {
    // words[ wordCounter ] = strTok.nextToken();
    // Logger.d( "word here = " + words[ wordCounter ] );
    // ++wordCounter;
    // }
    // for ( String word : words )
    // Logger.d( "word = " + word );
    //
    // userName = words[ 1 ];
    // senderAddress = words[ 2 ];
    //
    // if ( words[ 0 ].equals( Definitions.DISCOVER_PING ) ) {
    // Logger.d( "DISCOVER_PING received...." );
    // Logger.d( "cleanedup = " + cleanThisStringUp( words[ 2 ] ) );
    // if ( cleanThisStringUp( words[ 2 ] ).equals( cleanThisStringUp(
    // Definitions.IP_ADDRESS_INETADDRESS.getHostAddress() ) ) ) {
    // Logger.d( "yep, it's ours" );
    // } else {
    // discoverPingReceived( new String[] {
    // words[ 1 ],
    // cleanThisStringUp( words[ 2 ] )
    // } );
    // }
    // }
    //
    // if ( words[ 0 ].equals( Definitions.DISCOVER_ACK ) ) {
    // Logger.d( "DISCOVER_ACK received...." );
    // Logger.d( "cleanedup DISCOVER_ACK = " + cleanThisStringUp( words[ 2 ] )
    // );
    //
    // discoverAckReceived( new String[] {
    // words[ 1 ],
    // cleanThisStringUp( words[ 2 ] ),
    // words[ 3 ]
    // } );
    // }
    //
    // if ( words[ 0 ].equals( Definitions.DISCOVER_ACK2 ) ) {
    // Logger.d( "DISCOVER_ACK2 received...." );
    // Logger.d( "cleanedup DISCOVER_ACK2 = " + cleanThisStringUp( words[ 2 ] )
    // );
    //
    // discoverAck2Received( new String[] {
    // words[ 1 ],
    // cleanThisStringUp( words[ 2 ] ),
    // words[ 3 ]
    // } );
    // }
    //
    // if ( words[ 0 ].equals( Definitions.PLAY_REQUEST ) ) {
    // Logger.d( "PLAY_REQUEST received from : " + userName );
    // playRequestReceived( userName, senderAddress );
    //
    // }
    //
    // if ( words[ 0 ].equals( Definitions.PLAY_ACK ) ) {
    // Logger.d( "PLAY_ACK received from : " + words[ 3 ] + "; songName = " +
    // words[ 1 ] + "; artist = " + words[ 2 ] );
    // playAckReceived( words[ 2 ], words[ 1 ], words[ 3 ] );
    // }
    //
    // }

    private void dealWithReceivedPacketNew( DatagramPacket packet ) {
        try {
            String payload = new String( packet.getData(), packet.getOffset(), packet.getLength() );
            JSONObject jsonReceived = new JSONObject( payload );
            String messageType = jsonReceived.getString( Definitions.MSG_TYPE );
            String senderAddress = jsonReceived.getString( Definitions.SENDER_ADDRESS );
            String senderUserName = jsonReceived.getString( Definitions.SENDER_UNAME );
            Logger.d( "ShaveService.dealWithReceivedPacket: Message type received = " + messageType );

            if ( messageType.equals( Definitions.DISCOVER_PING ) ) {
                if ( senderAddress.equals( cleanThisStringUp( Definitions.IP_ADDRESS_INETADDRESS.getHostAddress() ) ) ) {
                    Logger.d( "ShaveService.dealWithReceivedPacket: DISCOVER_PING is our own, ignoring.." );
                } else {
                    discoverPingReceived( senderUserName, jsonReceived.getString( Definitions.SENDER_ADDRESS ) );
                }
            } else if ( messageType.equals( Definitions.DISCOVER_ACK ) ) {
                discoverAckReceived( jsonReceived.getString( "upload_port" ), senderUserName, senderAddress );
            } else if ( messageType.equals( Definitions.DISCOVER_ACK2 ) ) {
                discoverAck2Received( jsonReceived.getString( "upload_port" ), senderUserName, senderAddress );
            } else if ( messageType.equals( Definitions.PLAY_REQUEST ) ) {
                playRequestReceived( senderUserName, senderAddress );
            } else if ( messageType.equals( Definitions.PLAY_ACK ) ) {
                playAckReceived( jsonReceived.getString( "song_artist" ), jsonReceived.getString( "song_title" ), senderUserName );
            }
        } catch ( JSONException e ) {
            e.printStackTrace();
        }
    }

    private void playAckReceived( String artistName, String songName, String userName ) {
        Intent intent = new Intent( Definitions.INTENT_SONG_PLAYING );
        Logger.d( "playAckReceived : sending broadcast.." );
        intent.putExtra( "user_name", userName );
        intent.putExtra( "song_name", songName );
        intent.putExtra( "artist_name", artistName );
        this.sendBroadcast( intent );
    }

    private void discoverAckReceived( String downloadPort, String userName, String senderAddress ) {
        notePeer( userName, senderAddress );
        Logger.d( "ShaveService.discoverAckReceived: peerMap = " + peerMap.toString() );
        downloadPortMap.put( userName, Integer.parseInt( downloadPort ) );
        alreadyAssignedPorts.add( Integer.parseInt( downloadPort ) );

        // send out ACK2. This is to tell the peer about out uploadPort.
        String uploadPort = getUnassignedPort();
        uploadPortMap.put( userName, Integer.parseInt( uploadPort ) );
        alreadyAssignedPorts.add( Integer.parseInt( uploadPort ) );
        // sendMessage( senderAddress, Definitions.DISCOVER_ACK2 + ":" +
        // uploadPort );
        JSONObject message = new JSONObject();
        try {
            message.put( Definitions.MSG_TYPE, Definitions.DISCOVER_ACK2 );
            message.put( "upload_port", uploadPort );
        } catch ( JSONException e ) {
            Logger.e( "Error sending DISCOVER_ACK2.." );
            e.printStackTrace();
        }
        sendMessageNew( senderAddress, message );

        Toast toast = Toast.makeText( this, userName + " added!", Toast.LENGTH_SHORT );
        toast.show();
    }

    private void discoverAck2Received( String downloadPort, String userName, String senderAddress ) {
        notePeer( userName, senderAddress );
        Logger.d( "ShaveService.discoverAck2Received: peerMap = " + peerMap.toString() );
        downloadPortMap.put( userName, Integer.parseInt( downloadPort ) );
        alreadyAssignedPorts.add( Integer.parseInt( downloadPort ) );

        Toast toast = Toast.makeText( this, userName + " added!", Toast.LENGTH_SHORT );
        toast.show();
    }

    private void discoverPingReceived( String userName, String senderAddress ) {
        // add the requester to our peer - list and reply back
        String uploadPort = getUnassignedPort();
        uploadPortMap.put( userName, Integer.parseInt( uploadPort ) );
        alreadyAssignedPorts.add( Integer.parseInt( uploadPort ) );
        notePeer( userName, senderAddress );
        Logger.d( "ShaveService.discoverPingReceived: peerMap = " + peerMap.toString() );
        // sendMessage( senderAddress, Definitions.DISCOVER_ACK + ":" +
        // uploadPort );
        JSONObject message = new JSONObject();
        try {
            message.put( Definitions.MSG_TYPE, Definitions.DISCOVER_ACK );
            message.put( "upload_port", uploadPort );
        } catch ( JSONException e ) {
            e.printStackTrace();
        }
        sendMessageNew( senderAddress, message );

        Toast toast = Toast.makeText( this, userName + " added!", Toast.LENGTH_SHORT );
        toast.show();
    }

    private void playRequestReceived( String userName, String address ) {
        String songTitle;
        try {
            songTitle = getRecommendedSong( userName );
            String songPath = localMusicManager.getMusicMeta( songTitle ).getString( LocalMusicManager.META_PATH );
            String songArtist = localMusicManager.getMusicMeta( songTitle ).getString( LocalMusicManager.META_ARTIST );
            String songDuration = localMusicManager.getMusicMeta( songTitle ).getString( LocalMusicManager.META_DURATION );
            long songSize = getFileSize( songPath );
            Logger.d( "ShaveService.playRequestReceived: uploading song: " + songTitle + ", to: " + userName );

            // sendMessage( getPeerAddress( userName ), Definitions.PLAY_ACK +
            // ":" + songTitle + ":" + songArtist );
            JSONObject message = new JSONObject();
            message.put( Definitions.MSG_TYPE, Definitions.PLAY_ACK );
            message.put( "song_title", songTitle );
            message.put( "song_artist", songArtist );
            message.put( "song_duration", songDuration );

            // send the file meta in a separate message:
            sendMessageNew( getPeerAddress( userName ), message );

            uploadSong( userName, songPath, songSize );

        } catch ( Exception e ) {
            Toast.makeText( this, R.string.cannot_serve + userName, Toast.LENGTH_SHORT );
            e.printStackTrace();
        }

    }

    private void uploadSong( String userName, String songPath, long songSize ) {
        int destinationPort = uploadPortMap.get( userName );
        String destinationAddress = peerMap.get( userName );

        Uploader songUploader = new Uploader( destinationAddress, destinationPort, songPath, songSize );
        // Cancel previous uploader for this peer, if any & make note of the new
        // uploader:
        Uploader previousUploader = peerUploaderMap.get( userName );
        if ( previousUploader != null ) {
            previousUploader.cancel( true );
        }
        peerUploaderMap.put( userName, songUploader );
        songUploader.execute( getApplicationContext() );
    }

    private long getFileSize( String songPath ) {
        File tempFile = new File( songPath );
        if ( tempFile.exists() ) {
            return tempFile.length();
        } else {
            return -1;
        }

    }

    // TODO: improve exception handling.
    private String getRecommendedSong( String userName ) throws Exception {

        ArrayList<String> localMusicList = localMusicManager.getLatestLocalList();
        // 'peerMusicHistory' for this userName is empty, since we're serving
        // stuff up for the first time, return our first local song found:
        if ( peerMusicHistory.get( userName ) == null ) {
            if ( localMusicList.size() > 0 ) {
                // create a new 'history' for userName:
                peerMusicHistory.put( userName, new ArrayList<String>( Arrays.asList( localMusicList.get( 0 ) ) ) );
                // return localMusicManager.DEFAULT_MUSIC_DIRECTORY + "/" +
                // localMusicList.get( 0 );
                return localMusicList.get( 0 );
            } else {
                throw new Exception( "ShaveService.getRecommendedSong: Cannot read local Music listing!" );
            }

        } else { // We have served userName before.
            String songName = localMusicManager.requestSongForUser( userName, peerMusicHistory.get( userName ) );
            // We've served userName all our songs, so start over for him:
            if ( songName.equals( Definitions.SERVED_ALL_SONGS ) ) {
                resetPeerHistory( userName );
                getRecommendedSong( userName );
            } else {
                addSongToPeerHistory( userName, songName );
                // return localMusicManager.DEFAULT_MUSIC_DIRECTORY + "/" +
                // songName;
                return songName;
            }
        }
        return null;

    }

    private void resetPeerHistory( String userName ) {
        peerMusicHistory.remove( userName );
    }

    private void addSongToPeerHistory( String userName, String songName ) {
        ArrayList<String> songHistory = peerMusicHistory.get( userName );
        songHistory.add( songName );
        peerMusicHistory.put( userName, songHistory );
    }

    // return the last assigned port + '5' for now.
    private String getUnassignedPort() {
        String port;
        if ( alreadyAssignedPorts.size() == 0 ) {
            port = String.valueOf( Definitions.TRANSACTION_PORT_START );
        } else {
            port = String.valueOf( alreadyAssignedPorts.get( alreadyAssignedPorts.size() - 1 ) + 5 );
        }

        Logger.d( "ShaveService.getUnassignedPort: returning = " + port );
        return port;

    }

    String cleanThisStringUp( String string ) {
        return string.replace( "\\?", "" ).replace( "*", "" ).replace( "//", "" );
    }

    // public void sendMessage( String destinationAddress, String message ) {
    //
    // String sendMessage = message + ":" + getOurUserName() + ":" +
    // getOurIp().toString().replace( "/", "" ) + Definitions.END_DELIM;
    // byte[] testArr = sendMessage.getBytes();
    //
    // Logger.d( "sendMessage = " + sendMessage + ", len = " +
    // sendMessage.length() );
    // Logger.d( "testarr = " + testArr.toString() + ", len = " + testArr.length
    // );
    // try {
    // Logger.d( "destination address = " + InetAddress.getByName(
    // destinationAddress ) );
    // DatagramPacket sendPacket = new DatagramPacket( sendMessage.getBytes(),
    // sendMessage.getBytes().length, InetAddress.getByName( destinationAddress
    // ),
    // Definitions.GENERIC_SERVER_PORT );
    // mGenericSocket.send( sendPacket );
    // } catch ( Exception e ) {
    // e.printStackTrace();
    // }
    //
    // }

    public void sendMessageNew( String destinationAddress, JSONObject message ) {
        try {
            message.put( Definitions.SENDER_ADDRESS, getOurIp().toString().replace( "/", "" ) );
            message.put( Definitions.SENDER_UNAME, getOurUserName() );
            byte[] data = message.toString().getBytes();
            Logger.d( "ShaveService.sendMessage: destination address = " + InetAddress.getByName( destinationAddress ) );
            DatagramPacket sendPacket = new DatagramPacket( data, data.length, InetAddress.getByName( destinationAddress ), Definitions.GENERIC_SERVER_PORT );
            mGenericSocket.send( sendPacket );
        } catch ( Exception e ) {
            Logger.e( "Error while sending message.." );
            e.printStackTrace();
        }

    }

    private void notePeer( String userName, String address ) {
        if ( !peerList.contains( userName ) ) {
            peerList.add( userName );
        }
        peerMap.put( userName, address );
    }

    public String getNextPeer() {
        if ( peerIndex == ( peerList.size() - 1 ) ) {
            return peerList.get( peerIndex++ );
        } else if ( peerIndex == peerList.size() ) {
            // we have cycled through all our peers:
            peerIndex = 0;
            return peerList.get( peerIndex++ );
        } else {
            if ( peerList.size() > 0 ) {
                return peerList.get( peerIndex++ );
            } else {
                return null;
            }
        }

    }

    public int getDownloadPort( String userName ) {
        return downloadPortMap.get( userName );
    }

    public int getUploadPort( String userName ) {
        return uploadPortMap.get( userName );
    }

    public String getPeerAddress( String userName ) {
        return peerMap.get( userName );
    }

    public String getFileNameTrivial( String filePath ) {
        return filePath.substring( filePath.lastIndexOf( "/" ) + 1 );
    }

    public String getSongName() {
        return songName;
    }

    public void dumpMapsToLogs() {
        Logger.d( "ShaveService.dumpMapsToLogs: peerMap = " + peerMap.toString() );
        Logger.d( "ShaveService.dumpMapsToLogs: peerList = " + peerList.toString() );
        Logger.d( "ShaveService.dumpMapsToLogs: peerMusicHistory = " + peerMusicHistory.toString() );
        Logger.d( "ShaveService.dumpMapsToLogs: uploadPortMap = " + uploadPortMap.toString() );
        Logger.d( "ShaveService.dumpMapsToLogs: downloadPortMap = " + downloadPortMap.toString() );
        Logger.d( "ShaveService.dumpMapsToLogs: alreadyAssignedPorts = " + alreadyAssignedPorts.toString() );
        Logger.d( "ShaveService.dumpMapsToLogs: peerIndex = " + peerIndex );
        Logger.d( "ShaveService.dumpMapsToLogs: musicMetaMap = " + localMusicManager.musicMetaMap.toString() );

    }

    public void testApi() {
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";

        String[] projection = {
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION
        };

        Cursor cursor = getContentResolver().query( MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null, null );

        ArrayList<String> songs = new ArrayList<String>();
        while ( cursor.moveToNext() ) {
            songs.add( cursor.getString( 0 ) + "||" + cursor.getString( 1 ) + "||" + cursor.getString( 2 ) + "||" + cursor.getString( 3 ) + "||"
                    + cursor.getString( 4 ) + "||" + cursor.getString( 5 ) );
        }
        Logger.d( "gonna start dumping cursor.." );
        DatabaseUtils.dumpCursor( cursor );
    }

}
