package com.kishku7.m1.nav;

/** Binary min-heap on NavNode.f() with decrease-key via NavNode.heapIdx. */
final class NavHeap {

    private NavNode[] a = new NavNode[256];
    private int n;

    boolean isEmpty() {
        return n == 0;
    }

    void push(NavNode node) {
        if (n == a.length) {
            NavNode[] b = new NavNode[n * 2];
            System.arraycopy(a, 0, b, 0, n);
            a = b;
        }
        a[n] = node;
        node.heapIdx = n;
        n++;
        up(node.heapIdx);
    }

    NavNode pop() {
        NavNode top = a[0];
        n--;
        if (n > 0) {
            a[0] = a[n];
            a[0].heapIdx = 0;
            down(0);
        }
        a[n] = null;
        top.heapIdx = -1;
        return top;
    }

    /** Re-sift after a key decrease (g improved). */
    void update(NavNode node) {
        if (node.heapIdx >= 0) {
            up(node.heapIdx);
        }
    }

    private void up(int i) {
        while (i > 0) {
            int p = (i - 1) / 2;
            if (a[p].f() <= a[i].f()) {
                break;
            }
            swap(p, i);
            i = p;
        }
    }

    private void down(int i) {
        while (true) {
            int l = 2 * i + 1;
            int r = l + 1;
            int m = i;
            if (l < n && a[l].f() < a[m].f()) {
                m = l;
            }
            if (r < n && a[r].f() < a[m].f()) {
                m = r;
            }
            if (m == i) {
                return;
            }
            swap(m, i);
            i = m;
        }
    }

    private void swap(int i, int j) {
        NavNode t = a[i];
        a[i] = a[j];
        a[j] = t;
        a[i].heapIdx = i;
        a[j].heapIdx = j;
    }
}
