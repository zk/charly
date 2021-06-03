# Charly

<img src="https://raw.githubusercontent.com/zk/charly/main/app/static/img/charly-logo-right.svg"
 alt="Charly logo" align="right" width="200" />

Charly is a modern web framework for Clojure/Script


## Hot reload all the things

### Checklist
* ✅ CLJS source
* ✅ Static files (see `./static`)
* ✅ Dynamic CSS
* ✅ Routes
* ✅ Templates
* ⬜️ charly.edn

## Options

* `:id` -- Used in various parts as a unique identifier for this charly project
* `:project-root` -- Path to directory holding the project source relative to the working directory
* `:client-routes` -- Routes file for web frontend
* `:client-cicd`
  * `:git-user-email`, `:git-user-name` -- used to generate the deploy script



## Working on Charly

* [Figma](https://www.figma.com/file/9sfOfkNHPSiMKCyLS6w2KJ/Charly?node-id=0%3A1&viewport=655%2C505%2C1)



## Disbling Namespace Refresh

You might want to disable namespace refresh of clj and cljc files if you're working directly against the repl via your IDE's repl load commands.

+ Globally: Add `:disable-refresh-namespaces? true` to `charly.edn`. This prop is hot reloaded.
+ Namespace local: use (charly.tools-repl/disable-reload!) in the namespace (or pass the namespace as the first argument). charly.tools-repl/enable-reload! to enable reloading.
