package ying.andrews.lucene;

import org.trypticon.luceneupgrader.IndexUpgrader;
import org.trypticon.luceneupgrader.LuceneVersion;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

/**
 * A stand alone Java application to upgrade Lucene indexes.
 *
 * This program is based on open source project <a href="https://github.com/hakanai/luceneupgrader">luceneupgrader</a>.
 *
 * Current version allows Lucene indexes to be upgraded to 8.8.1.
 *
 * @author  <a href="mailto:ying.andrews@gmail.com">Ying Andrews</a>
 * @version 1.0
 * @since 1.0
 */
public class UpgradeTo8 {
    public static void main(String[] args) {
        System.out.println("You are about to upgrade an older lucene index to Version 8.8.1");
        System.out.println("*** WARNING: PLEASE MAKE A BACKUP OF THE OLD INDEX ***");
        System.out.print("Enter index location: (example: /User/john/lucene_indexes/agent_index): ");
        Scanner scanner = new Scanner(System.in);
        String indexPath = scanner.nextLine();
        Path textIndexPath = Paths.get(indexPath);
        try {
            new IndexUpgrader(textIndexPath)
                    .upgradeTo(LuceneVersion.VERSION_8);
        } catch (IOException e) {
            System.out.println("Upgrade failed!");
            System.out.println(e.getLocalizedMessage());
        }
        System.out.println("Upgrade completed!");
    }
}
