// Echo bot: repeats back whatever it receives.
import { run } from "./sdk";

run({
  onReady: (self, api) => api.log(`echo-bot ready as ${self.name || self.node}`),
  onMessage: (m, api) => {

    switch (m.text) {
      case "ping":
        return api.reply(m, "pong");
      case "hello":
        return api.reply(m, "hi there!");
      case "time":
        return api.reply(m, new Date().toISOString());
    }
    return Promise.resolve();
  }
});
