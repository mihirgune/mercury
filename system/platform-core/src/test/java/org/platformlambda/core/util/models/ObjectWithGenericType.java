package org.platformlambda.core.util.models;

public class ObjectWithGenericType<T> {

    private T content;
    private int id;

    public T getContent() {
        return content;
    }

    public void setContent(T content) {
        this.content = content;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

}