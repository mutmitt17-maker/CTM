package com.ctm.credit.model

/** Plain checks shared by the JUnit tests and by the offline runner (no test library needed). */
fun allVectorsMatch(): VerificationResult = CalculatorVerifier.verify(CalculatorSpec("v1", serverVectors))
