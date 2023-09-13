(ns bb-clj.core
  (:require ["vscode" :as vscode]
            ["fs" :as fs]
            [clojure.string :as str]))

;; rm -rf .shadow-cljs/ &&  npx shadow-cljs release :extension --npm
;; vsce package

;; lein with-profile +whidbey,+bb repl | $HOME/.vscode/extensions/ivana.bb-clj-0.1.0/repl-output-processor

(def settings (atom nil))

(defn read-settings! [^js context]
  (reset! settings {:extension-path (.-extensionPath context)
                    :cljstyle-path (.asAbsolutePath context "cljstyle")}))

;; (defn slurp [file] (-> (.readFileSync fs file "utf8") (.toString)))

(defn write-file [file string]
  (when-let [workspace-folder (aget (.-workspaceFolders vscode/workspace) 0)]
    (let [^js workspace-folder-uri (.-uri workspace-folder)
          file-path (str (.-fsPath workspace-folder-uri) "/" file)]
      (.writeFileSync fs file-path string)
      file-path)))

;; decorations ------------------------------------------------------------------------------------------

(def decorations (atom nil))

;; (def form-ok-decoration-type (vscode/window.createTextEditorDecorationType
;;                               (clj->js {:backgroundColor "#e9f3e9"})))

;; (def form-error-decoration-type (vscode/window.createTextEditorDecorationType
;;                                  (clj->js {:backgroundColor "#f3e7e7"})))

(def result-decoration-type (let [background-color "#F5FFFA" #_"#FFF5EE" #_"#FFFFF0" #_"#FFFAF0" #_"#FFF8DC" #_"#f5f5f5"]
                              (vscode/window.createTextEditorDecorationType
                               (clj->js {:after {:backgroundColor background-color
                                                 :margin "3px",
                                                 ;;  :border (str "2px solid " background-color)
                                                 }}))))

(defn show-decorations []
  (let [text-editor (.-activeTextEditor vscode/window)
        set-decorations (fn [decoration-type k]
                          (let [decorations-coll (vals (get @decorations k))]
                            (when (seq decorations-coll)
                              (.setDecorations text-editor decoration-type (clj->js decorations-coll)))))]
    ;; (set-decorations form-ok-decoration-type :forms-ok)
    ;; (set-decorations form-error-decoration-type :forms-error)
    (set-decorations result-decoration-type :results)))

(defn clear-decorations []
  (when @decorations
    (reset! decorations nil)
    (let [text-editor (.-activeTextEditor vscode/window)
          empty-decorations (clj->js [(vscode/Range. 0 0 0 0)])]
      ;; (.setDecorations text-editor form-ok-decoration-type    empty-decorations)
      ;; (.setDecorations text-editor form-error-decoration-type empty-decorations)
      (.setDecorations text-editor result-decoration-type empty-decorations))))

(defn subscribe-on-result-update []
  (when-let [workspace-folder (aget (.-workspaceFolders vscode/workspace) 0)]
    (let [pos->str (fn [^js pos] (str "(" (.-line pos) " " (.-character pos) ")"))
          range->str (fn [^js range] (str (pos->str (.-start range)) " " (pos->str (.-end range))))
          pattern (vscode/RelativePattern. workspace-folder ".repl-result")
          fs-watcher (vscode/workspace.createFileSystemWatcher pattern true false true)
          subscription (.onDidChange
                        fs-watcher
                        (fn [^js event]
                          (let [path (.-fsPath event)
                                text (-> (.readFileSync fs path "utf8") (.toString))
                                text (-> text
                                         str/trim
                                         (str/replace #"\s*\n+\s*" " "))
                                error? (str/starts-with? text "Syntax error")
                                {:keys [form-range result-range]} @decorations
                                ;; form-dec (clj->js {:range form-range})
                                decoration (clj->js {:range result-range
                                                     ;; :hoverMessage
                                                     :renderOptions {:after (cond-> {:contentText (str " => " text)}
                                                                              error? (assoc :color "#ff0000" #_"#ff7f7f"))}})]
                            (swap! decorations
                                   (fn [value]
                                     (-> value
                                         ;;  (assoc-in [(if error?
                                         ;;               :forms-error
                                         ;;               :forms-ok) (range->str form-range)] form-dec)
                                         (assoc-in [:results (range->str result-range)] decoration)
                                         (dissoc :form-range :result-range))))
                            (show-decorations))))]
      ;; subscription.dispose(); // stop listening
      (.onDidChangeTextDocument vscode/workspace #(clear-decorations))
      (.onDidChangeActiveTextEditor vscode/window #(clear-decorations)))))

;; format ------------------------------------------------------------------------------------------

(defn- brackets-wrap   [text n] (str (.repeat "(" n) text (.repeat ")" n)))
(defn- brackets-unwrap [text n] (subs text n (- (.-length text) n)))

(defn format-form [^js text-editor ^js edit]
  (-> (vscode/commands.executeCommand "editor.action.selectToBracket")
      (.then #(vscode/commands.executeCommand "editor.action.selectToBracket")) ;; set cursor to the end of selection
      (.then (fn [_]
               (let [document (.-document text-editor)
                     selection (.-selection text-editor)
                     text (.getText document selection)
                     shift (.-character (.-start selection))
                     pretty (-> (.execSync (js/require "child_process")
                                           (str (:cljstyle-path @settings) " pipe")
                                           (clj->js {:input (-> text (brackets-wrap shift))
                                                     :cwd (:extension-path @settings)
                                                     :encoding "utf8"
                                                     :windowsHide true}))
                                str/trimr
                                (brackets-unwrap shift)
                                ;; (str/replace #"\n{3,}" "\n\n") removes empty lines inside multiline strings too :(
                                )]
                 (when (not= pretty text)
                   (.edit text-editor (fn [selected-text] (.replace selected-text selection pretty)))))))
      (.then #(vscode/commands.executeCommand "cancelSelection"))
      ;; (.then #(vscode/commands.executeCommand "cursorUndo"))
      ;; (.then #(vscode/commands.executeCommand "cursorUndo"))
      (.catch #(vscode/window.showErrorMessage (str %)))))

;; terminal ------------------------------------------------------------------------------------------

(defn clear-terminal-and-decorations []
  (-> (vscode/commands.executeCommand "workbench.action.terminal.clear")
      (.then #(vscode/window.showTextDocument (.-document (.-activeTextEditor vscode/window))))
      (.then #(clear-decorations))
      (.catch #(vscode/window.showErrorMessage (str %)))))

(defn- send-to-terminal [text]
  (-> (vscode/commands.executeCommand "workbench.action.terminal.scrollToBottom")
      (.then #(.sendText vscode/window.activeTerminal text))
      (.catch #(vscode/window.showErrorMessage (str %)))))

(defn- send-to-terminal-via-repl-input-file [text]
  (let [file-name ".repl-input"]
    (write-file file-name text)
    (send-to-terminal (str ",(load-file \"" file-name "\")"))))

(defn- get-selection-text-set-decorations-range [^js text-editor _]
  (let [document (.-document text-editor)
        selection (.-selection text-editor)
        ;; remember current form range for selecting it on result evaluated
        _ (swap! decorations assoc
                 :form-range (vscode/Range. (.-start selection) (.-end selection))
                 :result-range (vscode/Range. (.-end selection) (.-end selection)))
        text (.getText document selection)]
    text))

(defn load-file-in-terminal [^js text-editor ^js edit]
  (when vscode/window.activeTerminal
    (-> (vscode/commands.executeCommand "workbench.action.files.save")
        (.then (fn [_]
                 (let [document (.-document text-editor)
                       file-path (.-fileName document)
                       ns-symbol (-> (.-text (.lineAt document 0))
                                     (str/replace #"\(ns\s+" "")
                                     str/trim
                                     symbol)]
                   (send-to-terminal-via-repl-input-file (str "(do (load-file \"" file-path "\") (symbol \"\"))"))
                   ;; (send-to-terminal (str "(in-ns '" ns-symbol ")"))
                   (send-to-terminal (str "(ns " ns-symbol ")"))))))))

(defn run-form-in-terminal [^js text-editor ^js edit]
  (when vscode/window.activeTerminal
    (-> (vscode/commands.executeCommand "editor.action.selectToBracket")
        (.then #(vscode/commands.executeCommand "editor.action.selectToBracket")) ;; set cursor to the end of selection
        (.then (partial get-selection-text-set-decorations-range text-editor))
        (.then send-to-terminal-via-repl-input-file)
        (.then #(vscode/commands.executeCommand "cancelSelection"))
        (.catch #(vscode/window.showErrorMessage (str %))))))

(defn go-to-definition [^js text-editor ^js edit]
  (when vscode/window.activeTerminal
    (-> (vscode/commands.executeCommand "cursorWordStartLeft")
        (.then #(vscode/commands.executeCommand "cursorWordEndRightSelect"))
        (.then (partial get-selection-text-set-decorations-range text-editor))
        (.then (fn [text]
                 (send-to-terminal-via-repl-input-file
                  (str "(when-let [{:keys [file line column]} (meta (resolve '" text "))] (print (str (char 27) \"[1;35m\" \"" text "\" (char 27) \"[0m   \" file \":\" line \":\" column)) (symbol \"\"))"))))
        (.then #(vscode/commands.executeCommand "cancelSelection"))
        ;; (.then #(vscode/commands.executeCommand "cursorWordEndRight")) ;; cancel selection oll the occurences
        (.catch #(vscode/window.showErrorMessage (str %))))))

;; activate/deactivate ------------------------------------------------------------------------------------------

(defn register-disposable [^js context ^js disposable]
  (-> (.-subscriptions context)
      (.push disposable)))

(defn activate [^js context]
  (read-settings! context)
  (subscribe-on-result-update)
  (doseq [disposable (concat
                      (->> [["bb-clj.clearTerminalAndDecorations" clear-terminal-and-decorations]]
                           (map (fn [[command callback]] (vscode/commands.registerCommand command callback))))
                      (->> [["bb-clj.formatForm" format-form]
                            ["bb-clj.loadFileInTerminal" load-file-in-terminal]
                            ["bb-clj.runFormInTerminal" run-form-in-terminal]
                            ["bb-clj.goToDefinition" go-to-definition]]
                           (map (fn [[command callback]] (vscode/commands.registerTextEditorCommand command callback)))))]
    (register-disposable context disposable))
  ;; (vscode/window.showInformationMessage "bb-clj extensions activated")
  )

(defn deactivate []
  (vscode/window.showInformationMessage (str "deactivate")))
