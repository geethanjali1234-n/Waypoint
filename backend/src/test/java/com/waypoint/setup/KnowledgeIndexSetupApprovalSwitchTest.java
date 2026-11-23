package com.waypoint.setup;

import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The manual Atlas index-setup utilities do nothing, and touch no database, unless an operator holding the separate
 * approval switches them on. Nothing in the application ever starts them.
 */
class KnowledgeIndexSetupApprovalSwitchTest {

    @BeforeEach
    @AfterEach
    void switchOff() {
        System.clearProperty("waypoint.knowledge.index-setup.approved");
        KnowledgeChunkVectorIndexSetup.approvedForTests = false;
    }

    @Test
    void checkAndApplyRefuseWithoutTheSwitchAndNeverTouchTheDatabase() {
        MongoDatabase database = mock(MongoDatabase.class);
        KnowledgeChunkVectorIndexSetup setup = new KnowledgeChunkVectorIndexSetup(database);
        assertThat(KnowledgeChunkVectorIndexSetup.approved()).isFalse();
        assertThatThrownBy(setup::check).isInstanceOf(IllegalStateException.class).hasMessageContaining("disabled pending separate approval");
        assertThatThrownBy(setup::apply).isInstanceOf(IllegalStateException.class).hasMessageContaining("disabled pending separate approval");
        verifyNoInteractions(database);
    }

    @Test
    void theCommandLineToolRefusesWithoutTheSwitchAndNeverCreatesAClient() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = KnowledgeChunkVectorIndexSetupTool.run(new String[]{"--check"}, Map.of("MONGODB_URI", "mongodb://localhost/x"),
                uri -> { throw new AssertionError("no client may be created"); }, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err));
        assertThat(exit).isEqualTo(1);
        assertThat(err.toString()).contains("disabled pending separate approval");
    }

    @Test
    void noSystemPropertyOrEnvironmentValueCanSwitchItOnInTheApplication() {
        // Activation needs a reviewed code change; an ambient setting is never enough.
        System.setProperty("waypoint.knowledge.index-setup.approved", "true");
        assertThat(KnowledgeChunkVectorIndexSetup.approved()).isFalse();
    }
}
