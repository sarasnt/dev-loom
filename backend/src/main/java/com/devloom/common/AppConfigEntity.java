package com.devloom.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A persisted, non-secret app setting (key/value). See {@link AppConfigService}. */
@Entity
@Table(name = "app_config")
public class AppConfigEntity {

    @Id
    private String key;

    @Column(columnDefinition = "text")
    private String value;

    protected AppConfigEntity() {
    }

    public static AppConfigEntity of(String key, String value) {
        AppConfigEntity e = new AppConfigEntity();
        e.key = key;
        e.value = value;
        return e;
    }

    public String getKey() { return key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
