# bb-clj

Bare-bones VS Code plugin for Clojure

![alt text](https://user-images.githubusercontent.com/10473034/162594446-e55b6bc4-6634-4f3b-9169-975555ceef0e.png "view")

Please, don't be confused if 'bb' reminds you the [babashka](https://github.com/babashka) project - it's just a coincidence, abbreviation for bare-bones

## Options

 * code formatting allowing custom rules (used [cljstyle](https://github.com/greglook/cljstyle))
 * eval forms in REPL opened in integrated terminal
 * load file & switch namespace

## Create installation package

```shell
npm run package
```
Then open VS Code plugins list and select Install from VSIX

## Usage

### Leiningen

Add zero-character-prefixed prompt to used lein profile (or `:user` for common use)

```clojure
{
 ...
 :repl-options {:prompt (fn [ns] (str (char 0) ns "=> "))
                ...}
}
```

Open integrated terminal and start lein repl with this profile and redirect its output to `repl-output-processor` (file locates in extension folder)

```shell
lein with-profile +whidbey,+bb repl | $HOME/.vscode/extensions/ivana.bb-clj-0.1.0/repl-output-processor
```

### Clojure CLI

Create file `expect-clj` with followed content

```shell
#!/usr/bin/expect
spawn clojure -e "(clojure.main/repl :init #(apply require clojure.main/repl-requires) :prompt #(print (str (char 0) (ns-name *ns*) \"=> \")))"
interact
```

make it executable

```shell
chmod +x ./expect-clj
```

Open integrated terminal and start clojure cli repl via this file and redirect its output to `repl-output-processor`

```shell
./expect-clj | $HOME/.vscode/extensions/ivana.bb-clj-0.1.0/repl-output-processor
```

Set cursor near some Clojure code form and enjoy the `bb-clj: Run Form in Active Terminal` command
