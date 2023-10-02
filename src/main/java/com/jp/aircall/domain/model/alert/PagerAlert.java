package com.jp.aircall.domain.model.alert;

import java.util.UUID;
import lombok.Data;

/**
 * This class represents the pager internal alert lifecycle
 */
@Data
public class PagerAlert {

  public enum ALERT_STATUS {OPEN, ACKNOWLEDGED, CLOSED }

  private String id = UUID.randomUUID().toString();
  private String serviceId;
  private String alertMessage;
  private ALERT_STATUS status;
  private int levelNotified;
  private long creationTs;
  private long lastNotificationTs;
  private long acknowledgmentTs;
  private long closedTs;


  public PagerAlert(String serviceId, String alertMessage, ALERT_STATUS status, int levelNotified, long creationTs, long lastNotificationTs) {
    this.serviceId = serviceId;
    this.alertMessage = alertMessage;
    this.status = status;
    this.levelNotified = levelNotified;
    this.creationTs = creationTs;
    this.lastNotificationTs = lastNotificationTs;
  }
}
