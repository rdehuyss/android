
package st.alr.mqttitude.services;

import java.util.Date;
import java.util.List;
import java.util.Map;


import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;

import st.alr.mqttitude.ActivityLauncher;
import st.alr.mqttitude.ActivityMain;
import st.alr.mqttitude.App;
import st.alr.mqttitude.ActivityLauncher.ErrorDialogFragment;
import st.alr.mqttitude.R;
import st.alr.mqttitude.db.ContactLink;
import st.alr.mqttitude.db.ContactLinkDao;
import st.alr.mqttitude.db.DaoMaster;
import st.alr.mqttitude.db.DaoMaster.DevOpenHelper;
import st.alr.mqttitude.db.DaoSession;
import st.alr.mqttitude.db.WaypointDao;
import st.alr.mqttitude.model.Contact;
import st.alr.mqttitude.model.GeocodableLocation;
import st.alr.mqttitude.preferences.ActivityPreferences;
import st.alr.mqttitude.support.Defaults;
import st.alr.mqttitude.support.Events;
import st.alr.mqttitude.support.ReverseGeocodingTask;
import android.R.bool;
import android.app.Dialog;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.ContactsContract.RawContacts;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import de.greenrobot.event.EventBus;

public class ServiceApplication implements ProxyableService {
    private static SharedPreferences sharedPreferences;
    private SharedPreferences.OnSharedPreferenceChangeListener preferencesChangedListener;
    private NotificationManager notificationManager;
    private static NotificationCompat.Builder notificationBuilder;
    private static boolean playServicesAvailable;
    private GeocodableLocation lastPublishedLocation;
    private Date lastPublishedLocationTime;
    private boolean even = false;
    private Handler handler;
    private int mContactCount;
    private ServiceProxy context;

    private SQLiteDatabase db;
    private DevOpenHelper helper;
    private DaoSession daoSession;
    private DaoMaster daoMaster;
    private ContactLinkDao contactLinkDao;
    private WaypointDao waypointDao;

    
    @Override
    public void onCreate(ServiceProxy context) {
        this.context = context;
        checkPlayServices();
        
        helper = new DaoMaster.DevOpenHelper(context, "mqttitude-db", null);
        db = helper.getWritableDatabase();
        daoMaster = new DaoMaster(db);
        daoSession = daoMaster.newSession();
        contactLinkDao = daoSession.getContactLinkDao();
        waypointDao = daoSession.getWaypointDao();
        
        
        
        
        notificationManager = (NotificationManager) context
                .getSystemService(Context.NOTIFICATION_SERVICE);
        notificationBuilder = new NotificationCompat.Builder(context);

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);

        preferencesChangedListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreference, String key) {
                if (key.equals(Defaults.SETTINGS_KEY_NOTIFICATION_ENABLED))
                    handleNotification();
                else if (key.equals(Defaults.SETTINGS_KEY_CONTACTS_LINK_CLOUD_STORAGE))
                    updateAllContacts();
            }
        };

        handler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                onHandlerMessage(msg);
            }
        };

        sharedPreferences.registerOnSharedPreferenceChangeListener(preferencesChangedListener);
        handleNotification();
        
        mContactCount = getContactCount();
        context.getContentResolver().registerContentObserver(ContactsContract.Contacts.CONTENT_URI,
                true, mObserver);
        
        

    }

    public WaypointDao getWaypointDao() {
        return waypointDao;
    }

    public ContactLinkDao getContactLinkDao() {
        return contactLinkDao;
    }

    @Override
    public void onDestroy() {

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        return 0;
    }

   
    public static Map<String, Contact> getContacts() {
        return App.getContacts();
    }

    public void onEvent(Events.ContactLocationUpdated e) {
        // Updates a contact or allocates a new one

        Contact c = App.getContacts().get(e.getTopic());

        if (c == null) {
            Log.v(this.toString(), "Allocating new contact for " + e.getTopic());
            c = new st.alr.mqttitude.model.Contact(e.getTopic());
            updateContact(c);
        }

        c.setLocation(e.getGeocodableLocation());

        App.getContacts().put(e.getTopic(), c);
        
        // Fires a new event with the now updated or created contact to which
        // fragments can react
        EventBus.getDefault().post(new Events.ContactUpdated(c));

    }

    private int getContactCount() {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    ContactsContract.Contacts.CONTENT_URI, null, null, null,
                    null);
            if (cursor != null) {
                return cursor.getCount();
            } else {
                return 0;
            }
        } catch (Exception ignore) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return 0;
    }

    private ContentObserver mObserver = new ContentObserver(new Handler()) {

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            final int currentCount = getContactCount();            
            // We can only track the contact count so we cannot determine which contact was added, removed or updated. Thus we have to update all
            if (currentCount != mContactCount) {
                updateAllContacts();
            }
            mContactCount = currentCount;
        }

    };

    /**
     * @category NOTIFICATION HANDLING
     */
    private void handleNotification() {
        Log.v(this.toString(), "handleNotification()");
        ((ServiceProxy) context).stopForeground(true);
        if (notificationEnabled() || !playServicesAvailable)
            createNotification();

    }

    private boolean notificationEnabled() {
        return sharedPreferences.getBoolean(Defaults.SETTINGS_KEY_NOTIFICATION_ENABLED,
                Defaults.VALUE_NOTIFICATION_ENABLED);
    }

    private void createNotification() {
        Log.v(this.toString(), "createNotification");

        Intent resultIntent = new Intent(context, ActivityLauncher.class);
        resultIntent.setAction("android.intent.action.MAIN");
        resultIntent.addCategory("android.intent.category.LAUNCHER");

        resultIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent resultPendingIntent = PendingIntent.getActivity(context, 0, resultIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        notificationBuilder.setContentIntent(resultPendingIntent);

        PendingIntent pIntent = ServiceProxy.getPendingIntentForService(context,
                ServiceProxy.SERVICE_LOCATOR, Defaults.INTENT_ACTION_PUBLISH_LASTKNOWN, null);

        notificationBuilder.addAction(
                R.drawable.ic_upload,
                context.getString(R.string.publish),
                pIntent);

        updateNotification();
    }

    private static void showPlayServicesNotAvilableNotification() {
        NotificationCompat.Builder nb = new NotificationCompat.Builder(App.getContext());
        NotificationManager nm = (NotificationManager) App.getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        
        nb.setContentTitle(App.getContext().getString(R.string.app_name))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentText("Google Play Services are not available")
                .setPriority(android.support.v4.app.NotificationCompat.PRIORITY_MIN);
        nm.notify(Defaults.NOTIFCATION_ID, nb.build());

    }

    public void updateTicker(String text) {
        notificationBuilder.setTicker(text + ((even = even ? false : true) ? " " : ""));
        notificationBuilder.setSmallIcon(R.drawable.ic_notification);
        notificationManager.notify(Defaults.NOTIFCATION_ID, notificationBuilder.build());

        // if the notification is not enabled, the ticker will create an empty
        // one that we get rid of
        if (!notificationEnabled())
            notificationManager.cancel(Defaults.NOTIFCATION_ID);
    }

    public void updateNotification() {
        if (!notificationEnabled() || !playServicesAvailable)
            return;

        String title = null;
        String subtitle = null;
        long time = 0;

        if (lastPublishedLocation != null
                && sharedPreferences.getBoolean("notificationLocation", true)) {
            time = lastPublishedLocationTime.getTime();

            if (lastPublishedLocation.getGeocoder() != null
                    && sharedPreferences.getBoolean("notificationGeocoder", false)) {
                title = lastPublishedLocation.toString();
            } else {
                title = lastPublishedLocation.toLatLonString();
            }
        } else {
            title = context.getString(R.string.app_name);
        }

        subtitle = ServiceLocator.getStateAsString(context) + " | "
                + ServiceBroker.getStateAsString(context);

        notificationBuilder.setContentTitle(title);
        notificationBuilder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentText(subtitle)
                .setPriority(android.support.v4.app.NotificationCompat.PRIORITY_MIN);
        if (time != 0)
            notificationBuilder.setWhen(lastPublishedLocationTime.getTime());

        context.startForeground(Defaults.NOTIFCATION_ID, notificationBuilder.build());
        // notificationManager.notify(Defaults.NOTIFCATION_ID,
        // notificationBuilder.build());
    }

    public void onEventMainThread(Events.StateChanged.ServiceBroker e) {
        updateNotification();
    }

    public void onEventMainThread(Events.StateChanged.ServiceLocator e) {
        updateNotification();
    }

    private void onHandlerMessage(Message msg) {
        switch (msg.what) {
            case ReverseGeocodingTask.GEOCODER_RESULT:
                geocoderAvailableForLocation(((GeocodableLocation) msg.obj));
                break;
        }
    }

    private void geocoderAvailableForLocation(GeocodableLocation l) {
        if (l == lastPublishedLocation)
            updateNotification();
    }

    public void onEvent(Events.PublishSuccessfull e) {
        Log.v(this.toString(), "Publish successful");
        if (e.getExtra() != null && e.getExtra() instanceof GeocodableLocation) {
            GeocodableLocation l = (GeocodableLocation) e.getExtra();

            this.lastPublishedLocation = l;
            this.lastPublishedLocationTime = e.getDate();

            if (sharedPreferences.getBoolean("notificationGeocoder", false)
                    && l.getGeocoder() == null)
                (new ReverseGeocodingTask(context, handler)).execute(new GeocodableLocation[] {
                        l
                });

            updateNotification();

            if (sharedPreferences.getBoolean(Defaults.SETTINGS_KEY_TICKER_ON_PUBLISH,
                    Defaults.VALUE_TICKER_ON_PUBLISH))
                updateTicker(context.getString(R.string.statePublished));

        }
    }

    public static boolean checkPlayServices() {
        playServicesAvailable = ConnectionResult.SUCCESS == GooglePlayServicesUtil.isGooglePlayServicesAvailable(App.getContext());
        
        if(!playServicesAvailable)
            showPlayServicesNotAvilableNotification();
                
        return playServicesAvailable;

    }
    
    public void updateAllContacts() {
        for (Contact c : App.getContacts().values()) {
            updateContact(c);
            EventBus.getDefault().post(new Events.ContactUpdated(c));
        }
    }

    
    /* Resolves username and image either from a locally saved mapping or from synced cloud contacts. If no mapping is found, no mame is set and the default image is assumed*/
    void updateContact(Contact c) {

        long contactId = getContactId(c);        
        boolean found = false;

        if(contactId <= 0) {
            Log.v(this.toString(), "contactId could not be resolved for " + c.getTopic() );
            setContactImageAndName(c, null, null);
            return;
        } else {
            Log.v(this.toString(), "contactId for " + c.getTopic() + " was resolved to " + contactId);
        }
        
        Cursor cursor = context.getContentResolver().query(RawContacts.CONTENT_URI, null, ContactsContract.Data.CONTACT_ID +" = ?", new String[]{contactId+""}, null);
        if(!cursor.isAfterLast()) {

            while (cursor.moveToNext()) 
            {            
                Bitmap image = Contact.resolveImage(context.getContentResolver(), contactId); 
                String displayName = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));
                
                Log.v(this.toString(), "Resolved display Name: " + displayName + ", image: " + image + " for topic " + c.getTopic());
                c.setName(displayName);
                c.setUserImage(image);
                found = true;
                break;
            } 
        }

        if(!found)
            setContactImageAndName(c, null, null);

        cursor.close();

    }
    void setContactImageAndName(Contact c, Bitmap image, String name) {
        c.setName(name);
        c.setUserImage(image);
    }
    
    private long getContactId(Contact c) {
        if(ActivityPreferences.isContactLinkCloudStorageEnabled())
            return getContactIdFromCloud(c);
        else
            return getContactIdFromLocalStorage(c);            
    }
  
    public long getContactIdFromLocalStorage(Contact c) {
        ContactLink cl = contactLinkDao.load(c.getTopic());
       
        return cl != null ? cl.getContactId() : 0; 
    }
        
    public long getContactIdFromCloud(Contact c) {        
        Long cId = (long) 0;
        String imWhere = "("+ContactsContract.CommonDataKinds.Im.CUSTOM_PROTOCOL
                + " = ? COLLATE NOCASE) AND (" + ContactsContract.CommonDataKinds.Im.DATA
                + " = ? COLLATE NOCASE)";
        String[] imWhereParams = new String[] {
                "Mqttitude", c.getTopic()
        };
        
        Cursor cursor = context.getContentResolver().query(ContactsContract.Data.CONTENT_URI, null,
                imWhere, imWhereParams, null);

        cursor.move(-1);
        while (cursor.moveToNext()) {
            cId = cursor.getLong(cursor.getColumnIndex(ContactsContract.Data.CONTACT_ID));
            Log.v(this.toString(), "found matching raw contact id " + cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Im._ID))+" with contact id " + cId + " to be associated with topic "
                            + cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Im.DATA)));
            break;
        }
        cursor.close();
        return cId;
    }

    public void linkContact(Contact c, long contactId) {
        if(ActivityPreferences.isContactLinkCloudStorageEnabled()) {
            Log.e(this.toString(), "Saving a ContactLink to the cloud is not yet supported");
            return;            
        }
        Log.v(this.toString(), "Creating ContactLink from " + c.getTopic() + " to contactId " + contactId);

        ContactLink cl = new ContactLink(c.getTopic(), contactId);
        contactLinkDao.insertOrReplace(cl);        
        
        updateContact(c); 
        EventBus.getDefault().postSticky(new Events.ContactUpdated(c));
    }
    
    

}