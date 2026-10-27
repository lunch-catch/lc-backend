package com.lunchcatch.admin;

public final class AdminLoginAuditContext implements AutoCloseable {
    private static final ThreadLocal<Boolean> HANDLED = new ThreadLocal<>();
    private final Boolean previous;

    private AdminLoginAuditContext() {
        previous = HANDLED.get();
        HANDLED.set(false);
    }

    public static AdminLoginAuditContext open() {
        return new AdminLoginAuditContext();
    }

    public static void markHandled() {
        if (HANDLED.get() != null) {
            HANDLED.set(true);
        }
    }

    public boolean handled() {
        return Boolean.TRUE.equals(HANDLED.get());
    }

    @Override
    public void close() {
        if (previous == null) {
            HANDLED.remove();
        } else {
            HANDLED.set(previous);
        }
    }
}
