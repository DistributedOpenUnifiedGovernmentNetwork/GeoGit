/* Copyright (c) 2013 OpenPlans. All rights reserved.
 * This code is licensed under the BSD New License, available at the root
 * application directory.
 */

package org.geogit.cli.porcelain;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;

import org.geogit.api.GeoGIT;
import org.geogit.api.plumbing.ResolveGeogitDir;
import org.geogit.api.porcelain.CloneOp;
import org.geogit.api.porcelain.ConfigOp;
import org.geogit.api.porcelain.ConfigOp.ConfigAction;
import org.geogit.api.porcelain.ConfigOp.ConfigScope;
import org.geogit.api.porcelain.InitOp;
import org.geogit.cli.AbstractCommand;
import org.geogit.cli.CLICommand;
import org.geogit.cli.GeogitCLI;
import org.geogit.repository.Repository;
import org.neo4j.kernel.impl.util.FileUtils;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Throwables;

/**
 * Clones a repository into a newly created directory, creates remote-tracking branches for each
 * branch in the cloned repository (visible using {@code geogit branch -r}), and creates and checks
 * out an initial branch that is forked from the cloned repository's currently active branch.
 * <p>
 * After the clone, a plain {@code geogit fetch} without arguments will update all the
 * remote-tracking branches, and a {@code geogit pull} without arguments will in addition merge the
 * remote master branch into the current master branch, if any.
 * <p>
 * This default configuration is achieved by creating references to the remote branch heads under
 * {@code refs/remotes/origin} and by initializing {@code remote.origin.url} and
 * {@code remote.origin.fetch} configuration variables.
 * <p>
 * CLI proxy for {@link CloneOp}
 * <p>
 * Usage:
 * <ul>
 * <li> {@code geogit clone [--branch <name>] <repository> [<directory>]}
 * </ul>
 * 
 * @see CloneOp
 */
@Parameters(commandNames = "clone", commandDescription = "Clone a repository into a new directory")
public class Clone extends AbstractCommand implements CLICommand {

    @Parameter(names = { "-b", "--branch" }, description = "Branch to checkout when clone is finished.")
    private String branch;

    @Parameter(names = { "--depth" }, description = "Depth of the clone.  If depth is less than 1, a full clone will be performed.")
    private int depth = 0;

    @Parameter(names = { "--filter" }, description = "Ini filter file.  This will create a sparse clone.")
    private String filterFile;

    @Parameter(description = "<repository> [<directory>]")
    private List<String> args;

    /**
     * Executes the clone command using the provided options.
     * 
     * @param cli
     * @see org.geogit.cli.AbstractCommand#runInternal(org.geogit.cli.GeogitCLI)
     */
    @Override
    public void runInternal(GeogitCLI cli) throws Exception {
        checkState(args != null && args.size() > 0, "You must specify a repository to clone.");
        checkState(args.size() < 3, "Too many arguments provided.");
        if (filterFile != null) {
            checkArgument(branch != null,
                    "Sparse Clone: You must explicitly specify a remote branch to clone by using '--branch <branch>'.");
        }

        String repoURL = args.get(0);

        try {
            final File repoDir;
            {
                File currDir = cli.getPlatform().pwd();
                if (args != null && args.size() == 2) {
                    String target = args.get(1);
                    File f = new File(target);
                    if (!f.isAbsolute()) {
                        f = new File(currDir, target).getCanonicalFile();
                    }
                    repoDir = f;
                    if (!repoDir.exists() && !repoDir.mkdirs()) {
                        throw new IllegalStateException("Can't create directory "
                                + repoDir.getAbsolutePath());
                    }

                    // Construct a non-relative repository URL
                    URI repoURI = URI.create(repoURL);
                    String protocol = repoURI.getScheme();
                    if (protocol == null || protocol.equals("file")) {
                        File repo = new File(repoURL);
                        if (!repo.isAbsolute()) {
                            repo = new File(currDir, repoURL).getCanonicalFile();
                        }
                        repoURL = repo.toURI().getPath();
                    }

                } else {
                    repoDir = currDir;
                }
            }

            GeoGIT geogit = new GeoGIT(cli.getGeogitInjector(), repoDir);

            Repository repository = geogit.command(InitOp.class).call();
            checkState(repository != null,
                    "Destination path already exists and is not an empty directory.");
            cli.setGeogit(geogit);
            cli.getPlatform().setWorkingDir(repoDir);

        } catch (Exception e) {
            throw Throwables.propagate(e);
        }

        boolean sparse = false;

        if (filterFile != null) {
            try {
                final String FILTER_FILE = "filter.ini";

                File oldFilterFile = new File(filterFile);
                if (!oldFilterFile.exists()) {
                    throw new FileNotFoundException("No filter file found at " + filterFile + ".");
                }

                URL envHome = new ResolveGeogitDir(cli.getPlatform()).call();
                if (envHome == null) {
                    throw new IllegalStateException("Not inside a geogit directory");
                }
                if (!"file".equals(envHome.getProtocol())) {
                    throw new UnsupportedOperationException(
                            "Sparse clone works only against file system repositories. "
                                    + "Repository location: " + envHome.toExternalForm());
                }
                File repoDir;
                try {
                    repoDir = new File(envHome.toURI());
                } catch (URISyntaxException e) {
                    throw Throwables.propagate(e);
                }
                File newFilterFile = new File(repoDir, FILTER_FILE);

                FileUtils.copyFile(oldFilterFile, newFilterFile);
                cli.getGeogit().command(ConfigOp.class).setAction(ConfigAction.CONFIG_SET)
                        .setName("sparse.filter").setValue(FILTER_FILE).setScope(ConfigScope.LOCAL)
                        .call();
                sparse = true;
            } catch (Exception e) {
                throw new IllegalStateException("Unable to copy filter file at path " + filterFile
                        + " to the new repository.", e);
            }
        }

        if (sparse) {
            cli.getConsole()
                    .println(
                            "Performing a sparse clone into '" + cli.getPlatform().pwd().getName()
                                    + "'...");
        } else {
            cli.getConsole().println("Cloning into '" + cli.getPlatform().pwd().getName() + "'...");
        }

        CloneOp clone = cli.getGeogit().command(CloneOp.class);
        clone.setProgressListener(cli.getProgressListener());
        clone.setBranch(branch).setRepositoryURL(repoURL);
        clone.setDepth(depth);

        clone.call();

        cli.getConsole().println("Done.");
    }
}
