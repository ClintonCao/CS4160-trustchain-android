package nl.tudelft.cs4160.trustchain_android.SharedPreferences;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;

import nl.tudelft.cs4160.trustchain_android.inbox.InboxItem;
import nl.tudelft.cs4160.trustchain_android.message.MessageProto;

/**
 * Created by timbu on 18/12/2017.
 */

public class InboxItemStorage {

    private final static String INBOX_ITEM_KEY = "INBOX_ITEM_KEY:";

    public static ArrayList<InboxItem> getInboxItems(Context context) {
        InboxItem[] array = SharedPreferencesStorage.readSharedPreferences(context, INBOX_ITEM_KEY, InboxItem[].class);
        if (array != null) {
            return new ArrayList<InboxItem>(Arrays.asList(array));
        }
        return new ArrayList<InboxItem>();
    }


    public static void deleteAll(Context context) {
        SharedPreferencesStorage.writeSharedPreferences(context, INBOX_ITEM_KEY, null);
    }

    public static void addInboxItem(Context context, InboxItem inboxItem) {
        InboxItem[] array = SharedPreferencesStorage.readSharedPreferences(context, INBOX_ITEM_KEY, InboxItem[].class);

        if (array == null) {
            InboxItem[] inboxItems = new InboxItem[1];
            inboxItems[0] = inboxItem;
            SharedPreferencesStorage.writeSharedPreferences(context, INBOX_ITEM_KEY, inboxItems);
        } else {
            InboxItem[] inboxItems = new InboxItem[array.length + 1];
            for (int i = 0; i < array.length; i++) {
                inboxItems[i] = array[i];
                if (array[i].getPublicKey().equals(inboxItem.getPublicKey())) {
                    return;
                }
            }
            inboxItems[array.length] = inboxItem;
            SharedPreferencesStorage.writeSharedPreferences(context, INBOX_ITEM_KEY, inboxItems);
        }
    }

    public static void addHalfBlock(Context context, String pubKey, int halfBlockSequenceNumbe) {
        InboxItem[] array = SharedPreferencesStorage.readSharedPreferences(context, INBOX_ITEM_KEY, InboxItem[].class);

        if (array == null) {
            return;
        } else {
            InboxItem[] inboxItems = new InboxItem[array.length + 1];
            for (int i = 0; i < array.length; i++) {
                inboxItems[i] = array[i];
                if (array[i].getPublicKey().equals(pubKey)) {
                    InboxItem item = inboxItems[i];
                    item.addHalfBlocks(halfBlockSequenceNumbe);
                    inboxItems[i] = item;
                }
            }
        }
    }
}