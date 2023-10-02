package com.jp.aircall.domain.model.policy;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EscalationPolicy {

  @NotEmpty private String serviceId; //We are assuming that there are only one escalation policy by service so serviceID will act as EscalationPolicy Unique Id too

  @NotEmpty private List<Level> levels;

}
