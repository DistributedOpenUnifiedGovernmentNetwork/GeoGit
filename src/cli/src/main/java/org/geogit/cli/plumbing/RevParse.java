/* Copyright (c) 2011 TOPP - www.openplans.org. All rights reserved.
 * This code is licensed under the LGPL 2.1 license, available at the root
 * application directory.
 */

package org.geogit.cli.plumbing;

import java.io.File;
import java.net.URL;

import jline.console.ConsoleReader;

import org.geogit.api.GeoGIT;
import org.geogit.api.plumbing.ResolveGeogitDir;
import org.geogit.cli.AbstractCommand;
import org.geogit.cli.GeogitCLI;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

/**
 * Determines if the current directory is inside a geogit repository.
 * <p>
 * Usage:
 * <ul>
 * <li> {@code geogit rev-parse --resolve-geogit-dir}: check if the current directory is inside a
 * geogit repository and print out the repository location
 * <li> {@code geogit rev-parse --is-inside-work-tree}: check if the current directory is inside a
 * geogit repository and print out the repository location
 * </ul>
 */
@Parameters(commandNames = "rev-parse", commandDescription = "Resolve parameters according to the arguments")
public class RevParse extends AbstractCommand {

    @Parameter(names = "--resolve-geogit-dir", description = "Check if the current directory is inside a geogit repository and print out the repository location")
    private boolean resolve_geogit_dir;

    @Parameter(names = "--is-inside-work-tree", description = "Check if the current directory is inside a geogit repository and print out the repository location")
    private boolean is_inside_work_tree;

    /**
     * Executes the rev-parse command using the provided options.
     * 
     * @param cli
     * @see org.geogit.cli.AbstractCommand#runInternal(org.geogit.cli.GeogitCLI)
     */
    @Override
    protected void runInternal(GeogitCLI cli) throws Exception {
        GeoGIT geogit = cli.getGeogit();
        if (null == geogit) {
            geogit = cli.newGeoGIT();
        }
        if (resolve_geogit_dir) {
            resolveGeogitDir(cli.getConsole(), geogit);
        } else if (is_inside_work_tree) {
            isInsideWorkTree(cli.getConsole(), geogit);
        }

    }

    private void isInsideWorkTree(ConsoleReader console, GeoGIT geogit) throws Exception {
        URL repoUrl = geogit.command(ResolveGeogitDir.class).call();

        File pwd = geogit.getPlatform().pwd();

        if (null == repoUrl) {
            console.println("Error: not a geogit repository (or any parent) '"
                    + pwd.getAbsolutePath() + "'");
        } else {
            boolean insideWorkTree = !pwd.getAbsolutePath().contains(".geogit");
            console.println(String.valueOf(insideWorkTree));
        }
    }

    private void resolveGeogitDir(ConsoleReader console, GeoGIT geogit) throws Exception {

        URL repoUrl = geogit.command(ResolveGeogitDir.class).call();
        if (null == repoUrl) {
            File currDir = geogit.getPlatform().pwd();
            console.println("Error: not a geogit dir '"
                    + currDir.getCanonicalFile().getAbsolutePath() + "'");
        } else if ("file:".equals(repoUrl.getProtocol())) {
            console.println(new File(repoUrl.toURI()).getCanonicalFile().getAbsolutePath());
        } else {
            console.println(repoUrl.toExternalForm());
        }
    }

}
