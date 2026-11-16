package com.mdm.platform.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 不依赖数据库的纯单元测试：编号归一逻辑（编号变体比对与"原文保留"的基础）。
 */
class AttributeAssertionServiceTest {

    @Test
    void prefixAndSeparatorsAreStripped() {
        assertEquals("SN1", AttributeAssertionService.normalizeNumber("SN-0001", null));
        assertEquals("SN1", AttributeAssertionService.normalizeNumber("SIEMENS-SN-0001", "SIEMENS"));
        assertEquals("SN1", AttributeAssertionService.normalizeNumber("SIE-SN-1", "SIE"));
    }

    @Test
    void leadingZerosDroppedOnlyInTrailingDigitRun() {
        assertEquals("SN18", AttributeAssertionService.normalizeNumber("SN-0018", null));
        assertEquals("DRWMOTOR100B", AttributeAssertionService.normalizeNumber("DRW-MOTOR-100-B", null));
        assertEquals("20240201", AttributeAssertionService.normalizeNumber("2024-02-01", null));
    }

    @Test
    void differentNumbersStayDifferent() {
        assertNotEquals(AttributeAssertionService.normalizeNumber("SN-0001", null),
                AttributeAssertionService.normalizeNumber("SN-0018", null));
        // 规范号末段字母不同不能归一为同值（避免把 100A 当成 100B）
        assertNotEquals(AttributeAssertionService.normalizeNumber("DRW-MOTOR-100", null),
                AttributeAssertionService.normalizeNumber("DRW-MOTOR-100-B", null));
    }
}
