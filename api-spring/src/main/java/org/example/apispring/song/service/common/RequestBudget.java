package org.example.apispring.song.service.common;

import java.util.concurrent.atomic.AtomicInteger;

public class RequestBudget {
    private final AtomicInteger left;
    public RequestBudget(int max) { this.left = new AtomicInteger(Math.max(0, max)); }
    public boolean takeOne() {
        while (true) {
            int cur = left.get();
            if (cur <= 0) return false;
            if (left.compareAndSet(cur, cur - 1)) return true;
        }
    }
    public int remaining() { return left.get(); }
}
