package com.waypoint.setup;

import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class KnowledgeIndexSetupDisabledTest {
    @Test void directSetupCannotReadOrWriteTheDatabase() {
        var database = mock(MongoDatabase.class);
        var setup = new KnowledgeChunkVectorIndexSetup(database);
        assertThatThrownBy(setup::check).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(setup::apply).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(database);
    }

    @Test void commandLineSetupDoesNotCreateAClientForCheckOrApply() {
        for (String mode : new String[]{"--check", "--apply"}) {
            var created = new AtomicBoolean();
            var output = new PrintStream(new ByteArrayOutputStream());
            int result = KnowledgeChunkVectorIndexSetupTool.run(new String[]{mode}, Map.of(), connection -> {
                created.set(true);
                throw new AssertionError("Database client must not be created");
            }, output, output);
            assertThat(result).isNotZero();
            assertThat(created).isFalse();
        }
    }
}