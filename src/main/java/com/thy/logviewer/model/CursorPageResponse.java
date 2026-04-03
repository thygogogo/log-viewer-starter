package com.thy.logviewer.model;

import java.util.List;

/**
 * Cursor-based pagination response.
 * nextCursor is null when there are no more items.
 */
public class CursorPageResponse<T> {
    private List<T> content;
    private boolean hasNext;
    private String nextCursor;
    private int size;

    public CursorPageResponse(List<T> content, boolean hasNext, String nextCursor, int size) {
        this.content = content;
        this.hasNext = hasNext;
        this.nextCursor = nextCursor;
        this.size = size;
    }

    public List<T> getContent() {
        return content;
    }

    public void setContent(List<T> content) {
        this.content = content;
    }

    public boolean isHasNext() {
        return hasNext;
    }

    public void setHasNext(boolean hasNext) {
        this.hasNext = hasNext;
    }

    public String getNextCursor() {
        return nextCursor;
    }

    public void setNextCursor(String nextCursor) {
        this.nextCursor = nextCursor;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }
}

