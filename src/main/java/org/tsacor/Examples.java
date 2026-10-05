/* tsacor-java -- GPL (>= 2); see COPYRIGHTS.md and LICENSE.md. */
package org.tsacor;

import java.io.*;
import java.nio.file.*;

/** The bundled example data set (r_meta.xlsx of the R package: 20 studies, 2005-2024). */
public final class Examples {
    private Examples() {}

    public static DataSet load() throws IOException {
        try (InputStream in = Examples.class.getResourceAsStream("r_meta.xlsx")) {
            if (in == null) throw new IOException("The bundled example data set is missing from the jar.");
            File tmp = File.createTempFile("tsacor_example", ".xlsx");
            tmp.deleteOnExit();
            Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return DataLoader.loadXlsx(tmp);
        }
    }
}
