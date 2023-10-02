package com.jp.aircall.domain.model.policy;

import jakarta.validation.constraints.NotEmpty;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Level {

  @NotEmpty private Set<Target> targets;
}
