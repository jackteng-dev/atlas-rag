package dev.knowledge;

import java.util.List;
import static dev.knowledge.Models.*;

/** Providers can only select verbatim evidence, never create executable instructions or arbitrary links. */
public interface ExcerptProvider {
    String mode();
    ProviderResult select(String question, List<Source> evidence);
}
