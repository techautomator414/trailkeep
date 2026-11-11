// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The "send me a test" menu offers every mail Trailkeep ships (WP-25 part e): {@link TestMailService#TEMPLATES} is
 * exactly the base names of {@code templates/notification/*.subject.txt}, so a new mail cannot be left out of it.
 */
class TestMailTemplatesTest {

    @Test
    void theTestMenuListsExactlyTheShippedMails() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/templates/notification"))) {
            assertThat(files.map(file -> file.getFileName().toString())
                            .filter(name -> name.endsWith(".subject.txt"))
                            .map(name -> name.substring(0, name.length() - ".subject.txt".length())))
                    .containsExactlyInAnyOrderElementsOf(TestMailService.TEMPLATES);
        }
        assertThat(TestMailService.TEMPLATES).doesNotHaveDuplicates();
    }
}
