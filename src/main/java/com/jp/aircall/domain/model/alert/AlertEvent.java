package com.jp.aircall.domain.model.alert;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * This class represents an alert event forwarded from Alerting Service
 */
@Data
@AllArgsConstructor
public class AlertEvent {

  @NotEmpty private String message;
  @NotEmpty private String serviceId;

}
