/**
 * Jooby https://jooby.io
 * Apache License Version 2.0 https://jooby.io/LICENSE.txt
 * Copyright 2014 Edgar Espina
 */
package io.jooby.cli;

import picocli.CommandLine;

@CommandLine.Command(name = "exit", description = "Exit console")
public class Exit extends Command {
  @Override public void run(CommandContext ctx) {
    ctx.exit(0);
  }
}