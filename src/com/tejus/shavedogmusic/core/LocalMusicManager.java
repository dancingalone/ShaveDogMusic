package com.tejus.shavedogmusic.core;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;

import com.tejus.shavedogmusic.utils.Logger;

import android.content.ContentResolver;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.os.AsyncTask;
import android.os.Environment;
import android.provider.MediaStore;

/*
 * Here's where we deal with everything related to our local music list.
 * For now, everything's only persisted in memory, since ShaveDog has to refresh / establish sessions with peers if the service stops for any reason.
 * Can be persisted to disk too, if required. Tags, etc related to music can be dealt with.
 */

public class LocalMusicManager {
    // storing this value here, coz it could be usefiul for later
    long lastRefreshTime;
    public ArrayList<String> musicList = new ArrayList<String>();
    // set to public only for testing:
    public HashMap<String, JSONObject> musicMetaMap = new HashMap<String, JSONObject>();
    public String DEFAULT_MUSIC_DIRECTORY = Environment.getExternalStorageDirectory().toString() + "/" + Environment.DIRECTORY_MUSIC;
    private ContentResolver contentResolver;
    public static final String META_TITLE = "title";
    public static final String META_ARTIST = "artist";
    public static final String META_PATH = "path";
    public static final String META_DURATION = "duration";
    public static final String META_DISPLAYNAME = "name";
    public static final String META_NATIVE_ID = "native_row_id";

    public LocalMusicManager( ContentResolver contentResolver ) {
        this.contentResolver = contentResolver;
    }

    public ArrayList<String> getLatestLocalList() {

        try {
            lastRefreshTime = new LocalMusicListUpdater().execute().get();
        } catch ( Exception e ) {
            e.printStackTrace();
        }
        return musicList;
    }

    /*
     * Returns the time the list was done refreshing:
     */
    // Previous method, which looks in DEFAULT_MUSIC_DIRECTORY to find music:

    // private class LocalMusicListUpdater extends AsyncTask<Void, Void, Long> {
    //
    // @Override
    // protected Long doInBackground( Void... shaveADogNow ) {
    // File listOfFiles = new File( DEFAULT_MUSIC_DIRECTORY );
    //
    // List<String> songList = Arrays.asList( listOfFiles.list( new
    // SupportedExtensions() ) );
    // for ( String song : songList ) {
    // Logger.d( "LocalMusicListUpdater: adding song = " + song );
    // musicList.add( song );
    // }
    // return System.currentTimeMillis();
    //
    // }
    //
    // }

    private class LocalMusicListUpdater extends AsyncTask<Void, Void, Long> {
        @Override
        protected Long doInBackground( Void... shaveADogNow ) {

            String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";

            String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DURATION
            };

            Cursor cursor = contentResolver.query( MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null, null );

            while ( cursor.moveToNext() ) {
                String title = cursor.getString( 2 );
                musicList.add( title );
                JSONObject musicMeta = new JSONObject();
                try {
                    musicMeta.put( META_NATIVE_ID, cursor.getString( 0 ) );
                    musicMeta.put( META_ARTIST, cursor.getString( 1 ) );
                    musicMeta.put( META_PATH, cursor.getString( 3 ) );
                    musicMeta.put( META_DISPLAYNAME, cursor.getString( 4 ) );
                    musicMeta.put( META_DURATION, cursor.getString( 5 ) );
                } catch ( JSONException e ) {
                    Logger.e( "Error inserting meta for track: " + cursor.getString( 1 ) );
                    e.printStackTrace();
                }
                musicMetaMap.put( title, musicMeta );
            }
            Logger.d( "gonna start dumping cursor.." );
            DatabaseUtils.dumpCursor( cursor );
            return System.currentTimeMillis();

        }
    }

    public class SupportedExtensions implements FilenameFilter {
        ArrayList<String> extensions = Definitions.MUSIC_TYPES;

        public boolean accept( File dir, String name ) {
            Iterator<String> iterator = extensions.iterator();
            while ( iterator.hasNext() ) {
                return name.endsWith( iterator.next() );
            }
            return false;
        }
    }

    public long getLastRefreshTime() {
        return lastRefreshTime;
    }

    /*
     * We serve up the next song for userName here. For now, it's just the first
     * song not served to him yet. So we're not using his user name yet.
     */

    public String requestSongForUser( String userName, ArrayList<String> history ) {
        if ( musicList.size() == 0 ) {
            getLatestLocalList();
        }
        Iterator<String> iterator = musicList.iterator();
        while ( iterator.hasNext() ) {
            String localItem = iterator.next().toString();
            // return the first song not in userName's history:
            if ( !history.contains( localItem ) ) {
                Logger.d( "LocalMusicManager.requestSongForUser returning: " + localItem );
                return localItem;
            }
        }
        // if all our songs have been served before to userName:
        return Definitions.SERVED_ALL_SONGS;

    }

    public JSONObject getMusicMeta( String songTitle ) {
        return musicMetaMap.get( songTitle );
    }

}
