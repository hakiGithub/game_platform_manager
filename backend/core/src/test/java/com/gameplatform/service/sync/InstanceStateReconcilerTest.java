package com.gameplatform.service.sync;

import com.gameplatform.adapter.DeployAdapter.InstanceStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InstanceStateReconcilerTest {

    @Test
    void observedRunning_fromStopped_updatesToRunning() {
        InstanceStateReconciler.Decision decision =
                InstanceStateReconciler.reconcile(InstanceStatus.STOPPED.getCode(), InstanceStatus.RUNNING);

        assertThat(decision.shouldUpdate()).isTrue();
        assertThat(decision.targetStatus()).isEqualTo(InstanceStatus.RUNNING.getCode());
        assertThat(decision.remark()).isNull();
    }

    @Test
    void observedStopped_fromRunning_updatesToStopped() {
        InstanceStateReconciler.Decision decision =
                InstanceStateReconciler.reconcile(InstanceStatus.RUNNING.getCode(), InstanceStatus.STOPPED);

        assertThat(decision.shouldUpdate()).isTrue();
        assertThat(decision.targetStatus()).isEqualTo(InstanceStatus.STOPPED.getCode());
        assertThat(decision.remark()).isEqualTo("进程未运行");
    }

    @Test
    void observedError_isARealStateTransition() {
        InstanceStateReconciler.Decision decision =
                InstanceStateReconciler.reconcile(InstanceStatus.RUNNING.getCode(), InstanceStatus.ERROR);

        assertThat(decision.shouldUpdate()).isTrue();
        assertThat(decision.targetStatus()).isEqualTo(InstanceStatus.ERROR.getCode());
        assertThat(decision.remark()).isEqualTo("实例异常");
    }

    @Test
    void sameObservedState_isNoop() {
        InstanceStateReconciler.Decision decision =
                InstanceStateReconciler.reconcile(InstanceStatus.RUNNING.getCode(), InstanceStatus.RUNNING);

        assertThat(decision.shouldUpdate()).isFalse();
        assertThat(decision.targetStatus()).isEqualTo(InstanceStatus.RUNNING.getCode());
    }

    @Test
    void transitionRemark_canBeSuppliedByObservationSource() {
        InstanceStateReconciler.Decision decision = InstanceStateReconciler.reconcile(
                InstanceStatus.RUNNING.getCode(), InstanceStatus.STOPPED, "容器已退出");

        assertThat(decision.remark()).isEqualTo("容器已退出");
    }
}
