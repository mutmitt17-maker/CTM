package com.ctm.credit.network

import kotlin.test.Test
import kotlin.test.assertNull

class SyncScenariosTest {
    @Test fun happyPath() = assertNull(scenarioHappyPath())

    @Test fun connectionCutBeforeSending() = assertNull(scenarioConnectionCutBeforeSending())

    @Test fun answerLostAfterServerApplied() = assertNull(scenarioAnswerLostAfterServerApplied())

    @Test fun crashLeavesItemsSent() = assertNull(scenarioCrashLeavesItemsSent())

    @Test fun conflictHoldsBackDependents() = assertNull(scenarioConflictHoldsBackDependents())

    @Test fun dependentInSameBatchIsNotMarkedFailed() = assertNull(scenarioDependentInSameBatchIsNotMarkedFailed())

    @Test fun realFailureIsNotHidden() = assertNull(scenarioRealFailureIsNotHidden())

    @Test fun offerChangedKeepsNewOffer() = assertNull(scenarioOfferChangedKeepsNewOffer())

    @Test fun batchesOf50() = assertNull(scenarioBatchesOf50())

    @Test fun unauthorizedStopsAndKeepsKeys() = assertNull(scenarioUnauthorizedStopsAndKeepsKeys())

    @Test fun serverErrorRetriesLater() = assertNull(scenarioServerErrorRetriesLater())

    @Test fun emptyAnswerDoesNotLoopForever() = assertNull(scenarioEmptyAnswerDoesNotLoopForever())

    @Test fun correctionUsesNewKeyAndUnblocks() = assertNull(scenarioCorrectionUsesNewKeyAndUnblocks())

    @Test fun correctionRefusedForAppliedItem() = assertNull(scenarioCorrectionRefusedForAppliedItem())

    @Test fun errorClassifier() = assertNull(scenarioClassifier())

    @Test fun everyKnownCodeHasAFrenchMessage() = assertNull(scenarioEveryKnownCodeHasAFrenchMessageAndResolution())

    @Test fun actionNames() = assertNull(scenarioActionNames())

    @Test fun canSendGateHoldsEntityUntilReady() = assertNull(scenarioCanSendGateHoldsEntityUntilReady())
}
