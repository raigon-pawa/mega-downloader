/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd.cli;

import com.tonikelope.megabasterd.AccountStore;
import com.tonikelope.megabasterd.MainPanel;
import com.tonikelope.megabasterd.MegaAPI;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "accounts", mixinStandardHelpOptions = true, description = "Manage stored MEGA accounts.", subcommands = {
    AccountsCommand.Add.class, AccountsCommand.List.class, AccountsCommand.Remove.class
})
public class AccountsCommand {

    @Command(name = "add", mixinStandardHelpOptions = true, description = "Log in to a MEGA account and store its credentials (encrypted at rest if a master password is configured).")
    public static class Add implements Callable<Integer> {

        @Parameters(index = "0", description = "MEGA account email.")
        private String email;

        @Option(names = {"--password"}, description = "MEGA account password. If omitted, prompted interactively (not echoed, when a real console is attached).")
        private String password;

        @Override
        public Integer call() {
            MainPanel main_panel = CliBootstrap.start();

            String pass = password;
            if (pass == null) {
                char[] entered = main_panel.getUserPrompter().promptPassword("MEGA password for " + email + ":");
                if (entered == null || entered.length == 0) {
                    System.err.println("No password entered.");
                    main_panel.shutdownForCli(1);
                    return 1;
                }
                pass = new String(entered);
            }

            try {
                MegaAPI ma = new MegaAPI();

                String pincode = null;
                if (ma.check2FA(email)) {
                    pincode = main_panel.getUserPrompter().prompt2FACode(email);
                    if (pincode == null || pincode.isEmpty()) {
                        System.err.println("2FA code required and not provided.");
                        main_panel.shutdownForCli(1);
                        return 1;
                    }
                }

                ma.login(email, pass, pincode);

                new AccountStore(main_panel).persistMegaLogin(email, pass, ma);

                System.out.println("Account added: " + email);
                main_panel.shutdownForCli(0);
                return 0;

            } catch (Exception ex) {
                System.err.println("Login failed for " + email + ": " + ex.getMessage());
                main_panel.shutdownForCli(1);
                return 1;
            }
        }
    }

    @Command(name = "list", mixinStandardHelpOptions = true, description = "List stored MEGA account emails.")
    public static class List implements Callable<Integer> {

        @Override
        public Integer call() {
            MainPanel main_panel = CliBootstrap.start();
            main_panel.getMega_accounts().keySet().forEach(System.out::println);
            main_panel.shutdownForCli(0);
            return 0;
        }
    }

    @Command(name = "remove", mixinStandardHelpOptions = true, description = "Remove a stored MEGA account.")
    public static class Remove implements Callable<Integer> {

        @Parameters(index = "0", description = "MEGA account email.")
        private String email;

        @Override
        public Integer call() {
            MainPanel main_panel = CliBootstrap.start();
            try {
                new AccountStore(main_panel).deleteMegaAccount(email);
                System.out.println("Account removed: " + email);
                main_panel.shutdownForCli(0);
                return 0;
            } catch (Exception ex) {
                System.err.println("Failed to remove " + email + ": " + ex.getMessage());
                main_panel.shutdownForCli(1);
                return 1;
            }
        }
    }
}
