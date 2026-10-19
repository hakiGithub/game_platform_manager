package com.gameplatform.plugin.task;

/**
 * One progress update received while following a child task.
 *
 * @param childPercent  child task progress in 0-100
 * @param mappedPercent child progress mapped into the parent window
 * @param task          current child task snapshot
 */
public record ChildTaskProgress(int childPercent, int mappedPercent, TaskVO task) {
}
