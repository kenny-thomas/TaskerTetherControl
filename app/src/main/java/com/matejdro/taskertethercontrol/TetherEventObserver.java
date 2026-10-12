package com.matejdro.taskertethercontrol;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.concurrent.Executor;

/** Receives Android tethered interface and client snapshots via TetheringManager. */
final class TetherEventObserver {
    interface Listener {
        void onInterfaces(Collection<?> interfaces);
        void onClients(Collection<?> clients);
        void onError(Throwable error);
    }

    private final Object manager;
    private final Object callback;
    private final Method unregister;

    TetherEventObserver(Context context, Listener listener) throws Exception {
        Class<?> owner = Class.forName("android.net.TetheringManager");
        Class<?> type = Class.forName("android.net.TetheringManager$TetheringEventCallback");
        if (!type.isInterface()) throw new IllegalStateException("TetheringEventCallback is not an interface");
        manager = context.getSystemService("tethering");
        if (manager == null) throw new IllegalStateException("Tethering service missing");
        callback = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    try {
                        String name = method.getName();
                        if ("onTetheredInterfacesChanged".equals(name) &&
                                args != null && args.length > 0 && args[0] instanceof Collection<?>) {
                            listener.onInterfaces((Collection<?>) args[0]);
                        } else if ("onClientsChanged".equals(name) &&
                                args != null && args.length > 0 && args[0] instanceof Collection<?>) {
                            listener.onClients((Collection<?>) args[0]);
                        } else if ("equals".equals(name)) return proxy == args[0];
                        else if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                        else if ("toString".equals(name)) return "Tasker tethering observer";
                    } catch (Throwable e) {
                        listener.onError(e);
                    }
                    return null;
                });
        Method register = owner.getMethod("registerTetheringEventCallback", Executor.class, type);
        unregister = owner.getMethod("unregisterTetheringEventCallback", type);
        Executor executor = task -> new Handler(Looper.getMainLooper()).post(task);
        register.invoke(manager, executor, callback);
    }

    void close() throws Exception {
        unregister.invoke(manager, callback);
    }
}
