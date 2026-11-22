package com.exteraplugins.scam_marker;

import org.telegram.ui.ActionBar.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.StaticLayout;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextDetailCell;
import org.telegram.ui.ProfileActivity;

public class Main {

    private static final String TAG = "ScamMarker";
    private static final String CHANNEL_USERNAME = "exteragram_pidors";
    private static final String CHANNEL_LINK = "https://t.me/exteragram_pidors";
    private static final String PREFS = "scam_marker_prefs";
    private static final String KEY_USERS = "scam_user_ids";
    private static final String KEY_CHATS = "scam_chat_ids";
    private static final String KEY_MUTED_CHATS = "muted_dialog_ids";
    private static final String KEY_LAST_UPDATE = "last_update";
    private static final String LIST_ADAPTER_NAME = "org.telegram.ui.ProfileActivity$ListAdapter";
    private static final String DIALOG_CELL_NAME = "org.telegram.ui.Cells.DialogCell";
    private static final String CHAT_MSG_CELL_NAME = "org.telegram.ui.Cells.ChatMessageCell";

    private static final String CLS_RESOLVE_USERNAME = "org.telegram.tgnet.TLRPC$TL_contacts_resolveUsername";
    private static final String CLS_GET_HISTORY = "org.telegram.tgnet.TLRPC$TL_messages_getHistory";
    private static final String CLS_INPUT_PEER_CHANNEL = "org.telegram.tgnet.TLRPC$TL_inputPeerChannel";

    private static final Pattern ID_PATTERN = Pattern.compile("(?:^|[\\s🛑:–—=(]|^id:?|^ид:?)(?:id[:=\\s]*)?(\\d{6,12})(?=[\\s.,;:!?)\\]]|$)", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern USER_URL_PATTERN = Pattern.compile("tg://user\\?id=(\\d{5,12})", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATIVE_ID_PATTERN = Pattern.compile("[-–—]\\s*(100\\d{9,12})(?=[\\s.,;:!?)\\]]|$)", Pattern.MULTILINE);

    private static volatile boolean loaded = false;
    private static volatile boolean fetching = false;
    private static volatile boolean stopFetch = false;
    private static final StringBuilder logBuf = new StringBuilder();
    private static volatile String lastLog = "";

    private static final Set<XC_MethodHook.Unhook> hookRefs = new HashSet<XC_MethodHook.Unhook>();
    private static final Set<Long> scamUserIds = new HashSet<Long>();
    private static final Set<Long> scamChatIds = new HashSet<Long>();
    private static final Set<Long> mutedChats = new HashSet<Long>();

    private static final WeakHashMap<ProfileActivity, State> states = new WeakHashMap<ProfileActivity, State>();
    private static final WeakHashMap<TextDetailCell, ProfileActivity> scamCells = new WeakHashMap<TextDetailCell, ProfileActivity>();
    private static final WeakHashMap<StaticLayout, Integer> nameOriginalColors = new WeakHashMap<StaticLayout, Integer>();
    private static final WeakHashMap<StaticLayout, Boolean> layoutScamMap = new WeakHashMap<StaticLayout, Boolean>();
    private static final Set<Long> warnedChats = new HashSet<Long>();

    private static class State {
        boolean shouldInsert = false;
        long userId = 0;
        int row = -1;
        int typeCache = -1;
        Object typeAdapter = null;

        void adapterReset() {
            row = -1;
            typeCache = -1;
            typeAdapter = null;
        }
    }

    // ============================================================
    // lifecycle (called from python loader via reflection)
    // ============================================================

    public static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            loadCachedIds();
            installHooks();
            startFetch();
        } catch (Throwable t) {
            loaded = false;
            log("load error: " + t);
        }
    }

    public static void unload() {
        try {
            for (XC_MethodHook.Unhook h : hookRefs) {
                try {
                    h.unhook();
                } catch (Throwable ignored) {
                }
            }
            hookRefs.clear();
        } catch (Throwable ignored) {
        }
        stopFetch = true;
        synchronized (states) {
            states.clear();
        }
        synchronized (scamCells) {
            scamCells.clear();
        }
        synchronized (warnedChats) {
            warnedChats.clear();
        }
        loaded = false;
    }

    public static void pyRefresh() {
        loadCachedIds();
        startFetch();
    }

    public static void pyFlushConfig() {
    }

    public static void pyFlushConfigFull() {
    }

    public static String pyStatus() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("loaded=").append(loaded);
            sb.append(", hooks=").append(hookRefs.size());
            sb.append(", users=").append(scamUserIds.size());
            sb.append(", chats=").append(scamChatIds.size());
            sb.append(", muted=").append(mutedChats.size());
            sb.append(", fetching=").append(fetching);
            sb.append("\nLOG:\n").append(logBuffer());
            return sb.toString();
        } catch (Throwable t) {
            return "pyStatus error: " + t;
        }
    }

    // ============================================================
    // scam id list loading (from cache + @exteragram_pidors channel history)
    // ============================================================

    public static String pySample() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("ids: ");
            int n = 0;
            synchronized (scamUserIds) {
                for (Long id : scamUserIds) {
                    if (n++ >= 10) {
                        break;
                    }
                    sb.append(id).append(' ');
                }
            }
            synchronized (scamChatIds) {
                for (Long id : scamChatIds) {
                    if (n++ >= 10) {
                        break;
                    }
                    sb.append('c').append(id).append(' ');
                }
            }
            String s = sb.toString().trim();
            if (s.endsWith("ids:")) {
                return "ids: (empty)";
            }
            return s;
        } catch (Throwable t) {
            return "pySample error: " + t;
        }
    }

    private static void loadCachedIds() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) {
                log("loadCachedIds: applicationContext is null");
                return;
            }
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String usersCsv = p.getString(KEY_USERS, "");
            String chatsCsv = p.getString(KEY_CHATS, "");
            String mutedCsv = p.getString(KEY_MUTED_CHATS, "");
            synchronized (scamUserIds) {
                if (usersCsv != null && !usersCsv.isEmpty()) {
                    addCached(usersCsv, scamUserIds);
                }
            }
            synchronized (scamChatIds) {
                if (chatsCsv != null && !chatsCsv.isEmpty()) {
                    addCached(chatsCsv, scamChatIds);
                }
            }
            synchronized (mutedChats) {
                mutedChats.clear();
                if (mutedCsv != null && !mutedCsv.isEmpty()) {
                    addCached(mutedCsv, mutedChats);
                }
            }
            log("loadCachedIds: loaded " + scamUserIds.size() + " users, " + scamChatIds.size() + " chats, " + mutedChats.size() + " muted chats from cache");
        } catch (Throwable t) {
            log("loadCachedIds error: " + t);
        }
    }

    private static void addCached(String csv, Set<Long> out) {
        if (csv == null) {
            return;
        }
        String[] parts = csv.split(",");
        for (String s : parts) {
            if (s == null || s.length() == 0) {
                continue;
            }
            try {
                out.add(Long.parseLong(s.trim()));
            } catch (Throwable ignored) {
            }
        }
    }

    private static void muteWarningForChat(long dialogId) {
        if (dialogId == 0) {
            return;
        }
        synchronized (mutedChats) {
            mutedChats.add(Long.valueOf(dialogId));
        }
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx != null) {
                StringBuilder sb = new StringBuilder();
                synchronized (mutedChats) {
                    for (Long id : mutedChats) {
                        if (sb.length() > 0) {
                            sb.append(',');
                        }
                        sb.append(id);
                    }
                }
                SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                p.edit().putString(KEY_MUTED_CHATS, sb.toString()).commit();
                log("muted warning for dialog: " + dialogId);
            }
        } catch (Throwable t) {
            log("muteWarningForChat error: " + t);
        }
    }

    private static boolean isWarningMuted(long dialogId) {
        if (dialogId == 0) {
            return false;
        }
        synchronized (mutedChats) {
            return mutedChats.contains(Long.valueOf(dialogId));
        }
    }

    private static void startFetch() {
        if (fetching) {
            return;
        }
        fetching = true;
        stopFetch = false;
        new Thread(new Runnable() {
            @Override
            public void run() {
                fetchLoop();
            }
        }, "scam-marker-fetch").start();
    }

    private static void fetchLoop() {
        try {
            long[] ch = null;
            for (int i = 0; !stopFetch && i < 3; i++) {
                ch = resolveChannel();
                if (ch != null) {
                    break;
                }
                try {
                    Thread.sleep(1500);
                } catch (Throwable ignored) {
                }
            }
            if (stopFetch || ch == null) {
                log("fetchLoop: unable to resolve @" + CHANNEL_USERNAME);
                return;
            }
            StringBuilder all = new StringBuilder();
            Set<Long> entityUsers = new HashSet<Long>();
            int offsetId = 0;
            while (!stopFetch) {
                long[] batch = fetchBatch(ch[0], ch[1], offsetId, ch[2], all, entityUsers);
                if (batch == null) {
                    break;
                }
                int count = (int) batch[1];
                long minId = batch[0];
                if (count < 100 || minId <= 0) {
                    break;
                }
                offsetId = (int) minId;
                try {
                    Thread.sleep(300);
                } catch (Throwable ignored) {
                }
            }
            if (!stopFetch) {
                parseAndSave(all.toString(), entityUsers);
            }
        } catch (Throwable t) {
            log("fetchLoop error: " + t);
        } finally {
            fetching = false;
        }
    }

    private static long[] resolveChannel() {
        final CountDownLatch latch = new CountDownLatch(1);
        final long[] out = new long[3];
        try {
            Object req = newInstance(CLS_RESOLVE_USERNAME);
            if (req == null) {
                return null;
            }
            setField(req, "username", CHANNEL_USERNAME);
            sendRequest(req, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    try {
                        if (method.getParameterTypes().length != 2) {
                            return null;
                        }
                        Object error = args[1];
                        if (error != null) {
                            log("resolveChannel error from server: " + error);
                        } else if (args[0] != null) {
                            Object chats = getField(args[0], "chats");
                            if (chats instanceof List && !((List<?>) chats).isEmpty()) {
                                Object c = ((List<?>) chats).get(0);
                                out[0] = longField(c, "id");
                                out[1] = longField(c, "access_hash");
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    latch.countDown();
                    return null;
                }
            });
            latch.await(15, TimeUnit.SECONDS);
        } catch (Throwable t) {
            log("resolveChannel error: " + t);
        }
        if (out[0] == 0) {
            return null;
        }
        return out;
    }

    private static long[] fetchBatch(long channelId, long accessHash, int offsetId, long hash, final StringBuilder all, final Set<Long> entityUsers) {
        final CountDownLatch latch = new CountDownLatch(1);
        final long[] out = new long[2];
        try {
            Object req = newInstance(CLS_GET_HISTORY);
            Object peer = newInstance(CLS_INPUT_PEER_CHANNEL);
            if (req == null || peer == null) {
                return null;
            }
            setField(peer, "channel_id", channelId);
            setField(peer, "access_hash", accessHash);
            setField(req, "peer", peer);
            setField(req, "offset_id", offsetId);
            setField(req, "offset_date", 0);
            setField(req, "add_offset", 0);
            setField(req, "limit", 100);
            setField(req, "max_id", 0);
            setField(req, "min_id", 0);
            setField(req, "hash", hash);
            sendRequest(req, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    try {
                        if (method.getParameterTypes().length != 2) {
                            return null;
                        }
                        Object error = args[1];
                        if (error != null) {
                            log("fetchBatch error from server: " + error);
                        } else if (args[0] != null) {
                            Object list = getField(args[0], "messages");
                            if (list instanceof List) {
                                List<?> msgs = (List<?>) list;
                                int count = msgs.size();
                                long minId = 0;
                                for (int i = 0; i < count; i++) {
                                    Object m = msgs.get(i);
                                    if (m == null) {
                                        continue;
                                    }
                                    long id = longField(m, "id");
                                    if (id > 0 && (minId == 0 || id < minId)) {
                                        minId = id;
                                    }
                                    String text = strField(m, "message");
                                    if (text != null && text.trim().length() > 0) {
                                        all.append(text).append('\n');
                                    }
                                    try {
                                        Object ents = getField(m, "entities");
                                        if (ents instanceof List) {
                                            for (Object ent : (List<?>) ents) {
                                                if (ent != null) {
                                                    long uid = longField(ent, "user_id");
                                                    if (uid > 0) {
                                                        entityUsers.add(uid);
                                                    }
                                                    String url = strField(ent, "url");
                                                    if (url != null && url.contains("tg://user?id=")) {
                                                        try {
                                                            int uIdx = url.indexOf("id=");
                                                            long u = Long.parseLong(url.substring(uIdx + 3));
                                                            if (u > 0) {
                                                                entityUsers.add(u);
                                                            }
                                                        } catch (Throwable ignored) {
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                }
                                out[0] = minId;
                                out[1] = count;
                            } else {
                                log("fetchBatch: messages field missing");
                            }
                        } else {
                            log("fetchBatch: null response");
                        }
                    } catch (Throwable ignored) {
                    }
                    latch.countDown();
                    return null;
                }
            });
            latch.await(20, TimeUnit.SECONDS);
        } catch (Throwable t) {
            log("fetchBatch error: " + t);
            return null;
        }
        return out;
    }

    private static void sendRequest(Object req, InvocationHandler handler) {
        try {
            Class<?> rdCls = Class.forName("org.telegram.tgnet.RequestDelegate");
            Object delegate = Proxy.newProxyInstance(rdCls.getClassLoader(), new Class<?>[]{rdCls}, handler);
            ConnectionsManager cm = ConnectionsManager.getInstance(UserConfig.selectedAccount);
            if (cm == null) {
                log("sendRequest: ConnectionsManager is null");
                return;
            }
            Method m = cm.getClass().getMethod("sendRequest", Class.forName("org.telegram.tgnet.TLObject"), rdCls);
            m.invoke(cm, req, delegate);
        } catch (Throwable t) {
            log("sendRequest error: " + t);
        }
    }

    private static void parseAndSave(String text, Set<Long> extraUsers) {
        Set<Long> users = new HashSet<Long>();
        if (extraUsers != null) {
            users.addAll(extraUsers);
        }
        if (text != null && !text.isEmpty()) {
            Matcher m = ID_PATTERN.matcher(text);
            while (m.find()) {
                try {
                    long v = Long.parseLong(m.group(1));
                    if (v > 0) {
                        users.add(v);
                    }
                } catch (Throwable ignored) {
                }
            }
            Matcher mUrl = USER_URL_PATTERN.matcher(text);
            while (mUrl.find()) {
                try {
                    long v = Long.parseLong(mUrl.group(1));
                    if (v > 0) {
                        users.add(v);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        Set<Long> chats = new HashSet<Long>();
        if (text != null && !text.isEmpty()) {
            Matcher m2 = NEGATIVE_ID_PATTERN.matcher(text);
            while (m2.find()) {
                try {
                    long v = Long.parseLong(m2.group(1));
                    if (v > 0) {
                        chats.add(v);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        if (users.isEmpty() && chats.isEmpty()) {
            log("parseAndSave: no IDs found in fetched data, keeping existing cache (" + scamUserIds.size() + " users)");
            return;
        }

        synchronized (scamUserIds) {
            scamUserIds.addAll(users);
        }
        synchronized (scamChatIds) {
            scamChatIds.addAll(chats);
        }

        try {
            StringBuilder ub = new StringBuilder();
            synchronized (scamUserIds) {
                for (Long v : scamUserIds) {
                    if (ub.length() > 0) {
                        ub.append(',');
                    }
                    ub.append(v);
                }
            }
            StringBuilder cb = new StringBuilder();
            synchronized (scamChatIds) {
                for (Long v : scamChatIds) {
                    if (cb.length() > 0) {
                        cb.append(',');
                    }
                    cb.append(v);
                }
            }
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx != null) {
                SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                p.edit().putString(KEY_USERS, ub.toString())
                        .putString(KEY_CHATS, cb.toString())
                        .putLong(KEY_LAST_UPDATE, System.currentTimeMillis() / 1000)
                        .commit();
                log("parseAndSave: saved " + scamUserIds.size() + " users, " + scamChatIds.size() + " chats to cache");
            }
        } catch (Throwable t) {
            log("save error: " + t);
        }
    }

    private static boolean isScamUser(long uid) {
        if (uid <= 0) {
            return false;
        }
        synchronized (scamUserIds) {
            return scamUserIds.contains(Long.valueOf(uid));
        }
    }

    private static boolean isScamChat(long chatId) {
        if (chatId <= 0) {
            return false;
        }
        synchronized (scamChatIds) {
            return scamChatIds.contains(Long.valueOf(chatId));
        }
    }

    // ============================================================
    // hooks
    // ============================================================

    private static void installHooks() {
        // 1. Hook MessagesController.getUser to set native user.scam = true for scammers
        try {
            Class<?> mc = Class.forName("org.telegram.messenger.MessagesController");
            XC_MethodHook userHook = new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object res = param.getResult();
                        if (res instanceof TLRPC.User) {
                            TLRPC.User u = (TLRPC.User) res;
                            if (isScamUser(u.id)) {
                                u.scam = true;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            Set<XC_MethodHook.Unhook> set = XposedBridge.hookAllMethods(mc, "getUser", userHook);
            if (set != null) {
                hookRefs.addAll(set);
            }
            set = XposedBridge.hookAllMethods(mc, "getUserOrChat", userHook);
            if (set != null) {
                hookRefs.addAll(set);
            }
        } catch (Throwable t) {
            log("hook getUser error: " + t);
        }

        // 2. Hook ProfileActivity: ensure native scam badge + insert custom info row
        try {
            Set<XC_MethodHook.Unhook> set = XposedBridge.hookAllMethods(ProfileActivity.class, "updateProfileData", new XC_MethodHook() {
                @Override
                public void beforeHookedMethod(MethodHookParam param) {
                    try {
                        ProfileActivity act = (ProfileActivity) param.thisObject;
                        long uid = userId(act);
                        if (isScamUser(uid)) {
                            MessagesController mc = act.getMessagesController();
                            if (mc != null) {
                                TLRPC.User u = mc.getUser(Long.valueOf(uid));
                                if (u != null) {
                                    u.scam = true;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            if (set != null) {
                hookRefs.addAll(set);
            }
        } catch (Throwable t) {
            log("hook updateProfileData error: " + t);
        }

        try {
            Set<XC_MethodHook.Unhook> set = XposedBridge.hookAllMethods(ProfileActivity.class, "updateRowsIds", new XC_MethodHook() {
                @Override
                public void beforeHookedMethod(MethodHookParam param) {
                    try {
                        ProfileActivity act = (ProfileActivity) param.thisObject;
                        State st = state(act);
                        st.shouldInsert = false;
                        st.adapterReset();
                        long uid = userId(act);
                        if (isScamUser(uid)) {
                            st.shouldInsert = true;
                            st.userId = uid;
                            MessagesController mc = act.getMessagesController();
                            if (mc != null) {
                                TLRPC.User u = mc.getUser(Long.valueOf(uid));
                                if (u != null) {
                                    u.scam = true;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    try {
                        ProfileActivity act = (ProfileActivity) param.thisObject;
                        State st = state(act);
                        if (!st.shouldInsert) {
                            st.row = -1;
                            return;
                        }
                        int pos = computePosition(act);
                        st.row = pos;
                        int rc = getIntField(act, "rowCount");
                        if (rc != -1) {
                            setIntField(act, "rowCount", rc + 1);
                        }
                        shiftRows(act, pos);
                    } catch (Throwable ignored) {
                    }
                }
            });
            if (set != null) {
                hookRefs.addAll(set);
            }
        } catch (Throwable t) {
            log("hook updateRowsIds error: " + t);
        }

        try {
            Set<XC_MethodHook.Unhook> set = XposedBridge.hookAllMethods(Class.forName(LIST_ADAPTER_NAME), "getItemViewType", new XC_MethodHook() {
                @Override
                public void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object adapter = param.thisObject;
                        ProfileActivity act = (ProfileActivity) getField(adapter, "this$0");
                        if (act == null) {
                            return;
                        }
                        State st = state(act);
                        if (!st.shouldInsert || st.row == -1) {
                            return;
                        }
                        int pos = ((Number) param.args[0]).intValue();
                        if (pos != st.row) {
                            return;
                        }
                        int t = resolveType(st, adapter, act);
                        if (t != -1) {
                            param.setResult(Integer.valueOf(t));
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            if (set != null) {
                hookRefs.addAll(set);
            }
        } catch (Throwable t) {
            log("hook getItemViewType error: " + t);
        }

        try {
            Set<XC_MethodHook.Unhook> set = XposedBridge.hookAllMethods(Class.forName(LIST_ADAPTER_NAME), "onBindViewHolder", new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object adapter = param.thisObject;
                        ProfileActivity act = (ProfileActivity) getField(adapter, "this$0");
                        if (act == null) {
                            return;
                        }
                        State st = state(act);
                        int pos = ((Number) param.args[1]).intValue();
                        Object holder = param.args[0];
                        View itemView = (View) getField(holder, "itemView");
                        if (!(itemView instanceof TextDetailCell)) {
                            return;
                        }
                        final TextDetailCell cell = (TextDetailCell) itemView;
                        if (!st.shouldInsert || st.row == -1 || pos != st.row) {
                            synchronized (scamCells) {
                                scamCells.remove(cell);
                            }
                            return;
                        }
                        synchronized (scamCells) {
                            scamCells.put(cell, act);
                        }
                        String title = S("🚫 В SCAM СПИСКЕ ПЛАГИНОВ!", "🚫 ON THE SCAM PLUGIN LIST!");
                        String value = S("Статус безопасности", "Security status");
                        try {
                            cell.setTextAndValue(title, value, true);
                        } catch (Throwable t) {
                            log("setTextAndValue error: " + t);
                        }
                        setTitleColor(cell);
                        setValueColor(cell);
                        cell.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                showScamDialog(v.getContext());
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
            });
            if (set != null) {
                hookRefs.addAll(set);
            }
        } catch (Throwable t) {
            log("hook onBindViewHolder error: " + t);
        }

        try {
            Set<XC_MethodHook.Unhook> set = XposedBridge.hookAllMethods(TextDetailCell.class, "updateColors", new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    try {
                        TextDetailCell cell = (TextDetailCell) param.thisObject;
                        boolean isScam;
                        synchronized (scamCells) {
                            isScam = scamCells.containsKey(cell);
                        }
                        if (!isScam) {
                            return;
                        }
                        setTitleColor(cell);
                    } catch (Throwable ignored) {
                    }
                }
            });
            if (set != null) {
                hookRefs.addAll(set);
            }
        } catch (Throwable t) {
            log("hook updateColors error: " + t);
        }

        installDialogCellHooks();
        installChatMessageCellHooks();
        installChatActivityHook();
        installSpoilerHook();
    }

    private static void installDialogCellHooks() {
        try {
            Class<?> dc = Class.forName(DIALOG_CELL_NAME);
            XC_MethodHook setDialogHook = new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    try {
                        long dialogId = dialogIdOf(param);
                        if (dialogId != 0) {
                            applyDialogCellColor(param.thisObject, dialogId);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            Set<XC_MethodHook.Unhook> set = XposedBridge.hookAllMethods(dc, "setDialog", setDialogHook);
            if (set != null) {
                hookRefs.addAll(set);
            }
            XC_MethodHook colorsHook = new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    try {
                        long dialogId = currentDialogId(param.thisObject);
                        if (dialogId != 0) {
                            applyDialogCellColor(param.thisObject, dialogId);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            set = XposedBridge.hookAllMethods(dc, "updateColors", colorsHook);
            if (set != null) {
                hookRefs.addAll(set);
            }
            set = XposedBridge.hookAllMethods(dc, "buildLayout", colorsHook);
            if (set != null) {
                hookRefs.addAll(set);
            }
            XC_MethodHook drawHook = new XC_MethodHook() {
                @Override
                public void beforeHookedMethod(MethodHookParam param) {
                    try {
                        long dialogId = currentDialogId(param.thisObject);
                        if (dialogId != 0) {
                            applyDialogCellColor(param.thisObject, dialogId);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            set = XposedBridge.hookAllMethods(dc, "onDraw", drawHook);
            if (set != null) {
                hookRefs.addAll(set);
            }
        } catch (Throwable t) {
            log("installDialogCellHooks error: " + t);
        }
    }

    private static void installChatMessageCellHooks() {
        try {
            Class<?> cmc = Class.forName(CHAT_MSG_CELL_NAME);
            XC_MethodHook colorsHook = new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    try {
                        applyChatNameColor(param.thisObject);
                    } catch (Throwable ignored) {
                    }
                }
            };
            Set<XC_MethodHook.Unhook> set = XposedBridge.hookAllMethods(cmc, "setupTextColors", colorsHook);
            if (set != null) {
                hookRefs.addAll(set);
            }
            XC_MethodHook drawHook = new XC_MethodHook() {
                @Override
                public void beforeHookedMethod(MethodHookParam param) {
                    try {
                        applyChatNameColor(param.thisObject);
                    } catch (Throwable ignored) {
                    }
                }
            };
            set = XposedBridge.hookAllMethods(cmc, "drawNamesLayout", drawHook);
            if (set != null) {
                hookRefs.addAll(set);
            }
            set = XposedBridge.hookAllMethods(cmc, "onDraw", drawHook);
            if (set != null) {
                hookRefs.addAll(set);
            }

            XC_MethodHook msgHook = new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof MessageObject)) {
                            return;
                        }
                        MessageObject msg = (MessageObject) param.args[0];
                        long dialogId = msg.getDialogId();
                        if (dialogId > 0) {
                            if (isWarningMuted(dialogId)) {
                                return;
                            }
                            if (isScamUser(dialogId)) {
                                synchronized (warnedChats) {
                                    if (!warnedChats.add(Long.valueOf(dialogId))) {
                                        return;
                                    }
                                }
                                View cell = param.thisObject instanceof View ? (View) param.thisObject : null;
                                if (cell != null) {
                                    showScamWarningPM(cell.getContext(), dialogId);
                                }
                            }
                        } else if (dialogId < 0) {
                            if (isWarningMuted(dialogId)) {
                                return;
                            }
                            synchronized (warnedChats) {
                                if (warnedChats.contains(Long.valueOf(dialogId))) {
                                    return;
                                }
                            }
                            long senderId = msg.getFromChatId();
                            if (isScamUser(senderId)) {
                                synchronized (warnedChats) {
                                    if (!warnedChats.add(Long.valueOf(dialogId))) {
                                        return;
                                    }
                                }
                                View cell = param.thisObject instanceof View ? (View) param.thisObject : null;
                                if (cell != null) {
                                    showScamWarningGroup(cell.getContext(), dialogId);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        log("msg hook error: " + t);
                    }
                }
            };
            set = XposedBridge.hookAllMethods(cmc, "setMessageObject", msgHook);
            if (set != null) {
                hookRefs.addAll(set);
            }
        } catch (Throwable t) {
            log("installChatMessageCellHooks error: " + t);
        }
    }

    private static void installChatActivityHook() {
        try {
            Class<?> ca = Class.forName("org.telegram.ui.ChatActivity");
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    long dialogId = 0;
                    try {
                        dialogId = longField(param.thisObject, "dialog_id");
                    } catch (Throwable t) {
                        log("chat open field error: " + t);
                    }
                    try {
                        final long did = dialogId;
                        if (did > 0) {
                            if (isWarningMuted(did)) {
                                return;
                            }
                            if (isScamUser(did)) {
                                synchronized (warnedChats) {
                                    if (!warnedChats.add(Long.valueOf(did))) {
                                        return;
                                    }
                                }
                                final Object chat = param.thisObject;
                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        Context ctx = chatContext(chat);
                                        if (ctx != null) {
                                            showScamWarningPM(ctx, did);
                                        }
                                    }
                                }, 500);
                            }
                        } else if (did < 0) {
                            if (isWarningMuted(did)) {
                                return;
                            }
                            long chatId = -did;
                            if (isScamChat(chatId)) {
                                synchronized (warnedChats) {
                                    if (!warnedChats.add(Long.valueOf(did))) {
                                        return;
                                    }
                                }
                                final Object chat = param.thisObject;
                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        Context ctx = chatContext(chat);
                                        if (ctx != null) {
                                            showScamWarningGroup(ctx, did);
                                        }
                                    }
                                }, 500);
                            }
                        }
                    } catch (Throwable t) {
                        log("chat open hook error: " + t);
                    }
                }
            };
            Set<XC_MethodHook.Unhook> set = XposedBridge.hookAllMethods(ca, "onResume", hook);
            if (set != null) {
                hookRefs.addAll(set);
            }
            set = XposedBridge.hookAllMethods(ca, "onFragmentCreate", hook);
            if (set != null) {
                hookRefs.addAll(set);
            }
        } catch (Throwable t) {
            log("installChatActivityHook error: " + t);
        }
    }

    private static Context chatContext(Object chatActivity) {
        try {
            if (chatActivity instanceof Context) {
                return (Context) chatActivity;
            }
            Method m = chatActivity.getClass().getMethod("getParentActivity");
            Object pa = m.invoke(chatActivity);
            if (pa instanceof Context) {
                return (Context) pa;
            }
        } catch (Throwable t) {
            log("chatContext method error: " + t);
        }
        try {
            Object pa = getField(chatActivity, "parentActivity");
            if (pa instanceof Context) {
                return (Context) pa;
            }
        } catch (Throwable t) {
            log("chatContext field error: " + t);
        }
        return null;
    }

    private static long dialogIdOf(XC_MethodHook.MethodHookParam param) {
        if (param.args != null && param.args.length > 0 && param.args[0] instanceof Number) {
            return ((Number) param.args[0]).longValue();
        }
        return currentDialogId(param.thisObject);
    }

    private static long currentDialogId(Object cell) {
        try {
            Field f = cell.getClass().getDeclaredField("currentDialogId");
            f.setAccessible(true);
            return f.getLong(cell);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static boolean isScamDialog(long dialogId) {
        if (dialogId > 0) {
            return isScamUser(dialogId);
        }
        long chatId = -dialogId;
        if (chatId <= 0) {
            return false;
        }
        return isScamChat(chatId);
    }

    private static void applyDialogCellColor(Object cell, long dialogId) {
        if (cell == null) {
            return;
        }
        StaticLayout sl = (StaticLayout) getField(cell, "nameLayout");
        if (sl == null) {
            return;
        }
        boolean scam = isScamDialog(dialogId);
        setLayoutColor(sl, scam);
    }

    private static void applyChatNameColor(Object cell) {
        if (cell == null) {
            return;
        }
        Object user = getField(cell, "currentUser");
        if (user == null) {
            return;
        }
        long uid = longField(user, "id");
        if (uid <= 0) {
            return;
        }
        boolean scam = isScamUser(uid);
        StaticLayout sl = (StaticLayout) getField(cell, "nameLayout");
        if (sl == null) {
            return;
        }
        setLayoutColor(sl, scam);
    }

    private static void setLayoutColor(StaticLayout sl, boolean scam) {
        if (sl == null) {
            return;
        }
        synchronized (nameOriginalColors) {
            if (scam) {
                Integer orig = nameOriginalColors.get(sl);
                if (orig == null) {
                    orig = Integer.valueOf(sl.getPaint().getColor());
                    nameOriginalColors.put(sl, orig);
                }
                layoutScamMap.put(sl, Boolean.TRUE);
                int red = Theme.getColor(Theme.key_text_RedRegular);
                if (sl.getPaint().getColor() != red) {
                    sl.getPaint().setColor(red);
                }
            } else {
                Integer orig = nameOriginalColors.remove(sl);
                if (orig != null) {
                    sl.getPaint().setColor(orig.intValue());
                }
                layoutScamMap.remove(sl);
            }
        }
    }

    private static void applySpoilerLayoutColor(Object layout) {
        if (!(layout instanceof StaticLayout)) {
            return;
        }
        StaticLayout sl = (StaticLayout) layout;
        synchronized (layoutScamMap) {
            if (Boolean.TRUE.equals(layoutScamMap.get(sl))) {
                int red = Theme.getColor(Theme.key_text_RedRegular);
                if (sl.getPaint().getColor() != red) {
                    sl.getPaint().setColor(red);
                }
            }
        }
    }

    private static void installSpoilerHook() {
        try {
            Class<?> se = Class.forName("org.telegram.ui.Components.spoilers.SpoilerEffect");
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                public void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args != null && param.args.length > 0) {
                            applySpoilerLayoutColor(param.args[0]);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            Set<XC_MethodHook.Unhook> set = XposedBridge.hookAllMethods(se, "layoutDrawMaybe", hook);
            if (set != null) {
                hookRefs.addAll(set);
            }
        } catch (Throwable t) {
            log("installSpoilerHook error: " + t);
        }
    }

    private static State state(ProfileActivity act) {
        synchronized (states) {
            State st = states.get(act);
            if (st == null) {
                st = new State();
                states.put(act, st);
            }
            return st;
        }
    }

    private static int computePosition(ProfileActivity act) {
        int earliest = -1;
        String[] names = {"phoneRow", "usernameRow", "bioRow", "channelInfoRow", "userInfoRow"};
        for (int i = 0; i < names.length; i++) {
            int v = getIntField(act, names[i]);
            if (v != -1 && (earliest == -1 || v < earliest)) {
                earliest = v;
            }
        }
        if (earliest != -1) {
            return earliest;
        }
        int empty2 = getIntField(act, "emptyRow2");
        if (empty2 != -1) {
            return empty2 + 1;
        }
        int empty = getIntField(act, "emptyRow");
        if (empty != -1) {
            return empty + 1;
        }
        int start = getIntField(act, "infoStartRow");
        if (start != -1) {
            return start;
        }
        int count = getIntField(act, "rowCount");
        return count > 0 ? count : 0;
    }

    private static void shiftRows(ProfileActivity act, int threshold) {
        try {
            Field[] fields = act.getClass().getDeclaredFields();
            for (int i = 0; i < fields.length; i++) {
                Field f = fields[i];
                String name = f.getName();
                if (!name.contains("Row") && !name.contains("row")) {
                    continue;
                }
                if (name.equals("rowCount")) {
                    continue;
                }
                Class<?> t = f.getType();
                if (t != int.class && t != Integer.class) {
                    continue;
                }
                f.setAccessible(true);
                Object v = f.get(act);
                if (v == null) {
                    continue;
                }
                int iv = ((Number) v).intValue();
                if (iv != -1 && iv >= threshold) {
                    f.setInt(act, iv + 1);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static int resolveType(State st, Object adapter, ProfileActivity act) {
        synchronized (st) {
            if (st.typeCache != -1 && st.typeAdapter == adapter) {
                return st.typeCache;
            }
            int t = -1;
            String[] candidates = {"phoneRow", "usernameRow", "bioRow"};
            for (int i = 0; i < candidates.length; i++) {
                int c = getIntField(act, candidates[i]);
                if (c == -1 || c == st.row) {
                    continue;
                }
                try {
                    Object r = adapter.getClass().getMethod("getItemViewType", int.class).invoke(adapter, Integer.valueOf(c));
                    if (r instanceof Integer) {
                        t = ((Integer) r).intValue();
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            st.typeCache = t == -1 ? 2 : t;
            st.typeAdapter = adapter;
            return st.typeCache;
        }
    }

    // ============================================================
    // row visual + dialog
    // ============================================================

    private static void setTitleColor(TextDetailCell cell) {
        try {
            TextView tv = (TextView) publicField(cell, "textView");
            if (tv != null) {
                tv.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void setValueColor(TextDetailCell cell) {
        try {
            TextView tv = (TextView) publicField(cell, "valueTextView");
            if (tv != null) {
                tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void showScamDialog(Context ctx) {
        try {
            String title = S("🚫 Пользователь в списке скамеров", "🚫 User is on the scam list");
            String msg = S(
                    "Этот пользователь находится в базе распространителей вредоносных плагинов.\n\nДанные с канала @exteragram_pidors:\n" + CHANNEL_LINK,
                    "This user is on the database of malicious plugin distributors.\n\nData from @exteragram_pidors channel:\n" + CHANNEL_LINK);
            new AlertDialog.Builder(ctx)
                    .setTitle(title)
                    .setMessage(msg)
                    .setPositiveButton(S("ОК", "OK"), null)
                    .show();
        } catch (Throwable t) {
            log("showScamDialog error: " + t);
        }
    }

    private static void showScamWarningPM(final Context ctx, final long dialogId) {
        if (ctx == null || isWarningMuted(dialogId)) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (isWarningMuted(dialogId)) {
                        return;
                    }
                    String title = S("⚠️ Осторожно!", "⚠️ Be careful!");
                    String msg = S("Вы собираетесь начать общение с человеком из скам-базы плагинмейкеров ExteraGram.\n\nБудьте осторожны и не доверяйте ему.",
                            "You are about to start a conversation with a person from the ExteraGram plugin maker scam database.\n\nBe careful and do not trust them.");
                    new AlertDialog.Builder(ctx)
                            .setTitle(title)
                            .setMessage(msg)
                            .setPositiveButton(S("Понятно", "Got it"), null)
                            .setNegativeButton(S("Не показывать здесь", "Don't show here"), new AlertDialog.OnButtonClickListener() {
                                @Override
                                public void onClick(AlertDialog dialog, int which) {
                                    muteWarningForChat(dialogId);
                                }
                            })
                            .show();
                } catch (Throwable t) {
                    log("showScamWarningPM error: " + t);
                }
            }
        });
    }

    private static void showScamWarningGroup(final Context ctx, final long dialogId) {
        if (ctx == null || isWarningMuted(dialogId)) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (isWarningMuted(dialogId)) {
                        return;
                    }
                    String title = S("⚠️ Осторожно!", "⚠️ Be careful!");
                    String msg = S("В этом чате есть пользователи из скам-базы плагинмейкеров ExteraGram.\n\nБудьте осторожны и не доверяйте им.",
                            "There are users from the ExteraGram plugin maker scam database in this chat.\n\nBe careful and do not trust them.");
                    new AlertDialog.Builder(ctx)
                            .setTitle(title)
                            .setMessage(msg)
                            .setPositiveButton(S("Понятно", "Got it"), null)
                            .setNegativeButton(S("Не показывать здесь", "Don't show here"), new AlertDialog.OnButtonClickListener() {
                                @Override
                                public void onClick(AlertDialog dialog, int which) {
                                    muteWarningForChat(dialogId);
                                }
                            })
                            .show();
                } catch (Throwable t) {
                    log("showScamWarningGroup error: " + t);
                }
            }
        });
    }

    // ============================================================
    // helpers
    // ============================================================

    private static Object newInstance(String clsName) {
        try {
            return Class.forName(clsName).newInstance();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long userId(ProfileActivity act) {
        try {
            Field f = act.getClass().getDeclaredField("userId");
            f.setAccessible(true);
            return f.getLong(act);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int getIntField(Object obj, String name) {
        try {
            return getField(obj, name) instanceof Number ? ((Number) getField(obj, name)).intValue() : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static void setIntField(Object obj, String name, int value) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.setInt(obj, value);
        } catch (Throwable ignored) {
        }
    }

    private static Object getField(Object obj, String name) {
        if (obj == null) {
            return null;
        }
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static void setField(Object obj, String name, Object value) {
        if (obj == null) {
            return;
        }
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getField(name);
                f.set(obj, value);
                return;
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
    }

    private static void setField(Object obj, String name, long value) {
        if (obj == null) {
            return;
        }
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.setLong(obj, value);
                return;
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
    }

    private static void setField(Object obj, String name, int value) {
        if (obj == null) {
            return;
        }
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.setInt(obj, value);
                return;
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
    }

    private static long longField(Object obj, String name) {
        if (obj == null) {
            return 0;
        }
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getLong(obj);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return 0;
    }

    private static String strField(Object obj, String name) {
        try {
            Object v = getField(obj, name);
            return v instanceof String ? (String) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object publicField(Object obj, String name) {
        try {
            Field f = obj.getClass().getField(name);
            return f.get(obj);
        } catch (Throwable ignored) {
        }
        return getField(obj, name);
    }

    private static boolean isRussian() {
        try {
            Locale loc = null;
            try {
                loc = LocaleController.getInstance().getCurrentLocale();
            } catch (Throwable ignored) {
            }
            if (loc == null) {
                loc = Locale.getDefault();
            }
            if (loc != null) {
                String l = loc.getLanguage();
                if (l != null) {
                    l = l.toLowerCase();
                    if (l.startsWith("ru") || l.startsWith("be") || l.startsWith("uk") || l.startsWith("kk")) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String S(String ru, String en) {
        return isRussian() ? ru : en;
    }

    private static void log(String s) {
        lastLog = s;
        synchronized (logBuf) {
            logBuf.append(s).append('\n');
            int cut = logBuf.length() - 4000;
            if (cut > 0) {
                logBuf.delete(0, cut);
            }
        }
        android.util.Log.d(TAG, s);
    }

    private static String logBuffer() {
        synchronized (logBuf) {
            return logBuf.toString();
        }
    }
}
