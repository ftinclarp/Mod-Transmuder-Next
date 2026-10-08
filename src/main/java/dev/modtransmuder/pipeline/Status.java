package dev.modtransmuder.pipeline;

/** Lifecycle of a stage outcome (ARCHITECTURE §4). */
public enum Status {
    SUCCESS,
    FAILED,
    SKIPPED
}
