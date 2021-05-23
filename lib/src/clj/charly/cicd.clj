(ns charly.cicd
  (:require [rx.kitchen-sink :as ks]
            [charly.config :as config]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(defn dpr [cfg & ss]
  (when (:debug? cfg)
    (apply println ss)))

(defn env->cicd-config [env]
  (let [client-cicd (:client-cicd env)
        working-directory (or (:working-directory client-cicd) "./")
        action-name (or (:id env) "charly-build")
        project-root (or (:project-root env) "./")]
    (merge
      (:client-cicd env)
      {:debug? (:debug? env)
       :action-name action-name
       :working-directory working-directory
       :project-root project-root})))

(defn indent [s n]
  (->> (str/split s #"\n")
       (interpose (apply str "\n" (repeat n " ")))
       (apply str)))

(defn recreate-deploy-branch-script [env]
  "branch_name=$(git symbolic-ref -q HEAD)
branch_name=${branch_name##refs/heads/}
branch_name=deploy-${branch_name:-HEAD}

echo \"Regenerating $branch_name branch\"
git branch -D $branch_name || true
git checkout -b $branch_name")

(defn commit-and-push-web-deploy-branch-script [env]
  "branch_name=$(git symbolic-ref -q HEAD)
branch_name=${branch_name##refs/heads/}
branch_name=${branch_name:-HEAD}

git add .
git commit -am 'Regen site'
git push origin $branch_name -f")

(defn github-actions-template [{:keys [action-name
                                       git-user-name
                                       git-user-email
                                       working-directory]
                                :as env}]
  (assert action-name)
  (assert git-user-name)
  (assert git-user-email)
  (let []
    (format
      "name: %s
on: [push]
jobs:
  build-frontend:
    runs-on: ubuntu-latest
    steps:
      - run: echo \"🎉 The job was automatically triggered by a ${{ github.event_name }} event.\"
      - run: echo \"🐧 This job is now running on a ${{ runner.os }} server hosted by GitHub!\"
      - run: echo \"🔎 The name of your branch is ${{ github.ref }} and your repository is ${{ github.repository }}.\"
      - name: Check out repository code
        uses: actions/checkout@v2
      - run: git config --global user.email \"%s\"
      - run: git config --global user.name \"%s\"
      - run: echo \"💡 The ${{ github.repository }} repository has been cloned to the runner.\"
      - run: echo \"🖥️ The workflow is now ready to test your code on the runner.\"
      - name: List files in the repository
        run: |
          ls ${{ github.workspace }}
      - name: Recreate deploy branch
        run: |
          %s
        working-directory: %s
      - name: tools.deps-builder
        uses: vouch-opensource/tools.deps-build@1.0.1
        with:
          working-directory: %s
          alias: :build-web
      - name: Commit and push changes
        run: |
          %s
        working-directory: %s
      - run: echo \"🍏 This job's status is ${{ job.status }}.\""
      (str action-name)
      git-user-email
      git-user-name
      (indent (recreate-deploy-branch-script env) (count "          "))
      working-directory
      working-directory
      (indent (commit-and-push-web-deploy-branch-script env) (count "          "))
      working-directory
      working-directory)))

(defn workflow-path [cfg]
  (config/concat-paths
    [(or (:project-root cfg) "./")
     ".github/workflows/build-frontend.yml"]))

(defn ensure-github-actions-directory [cfg]
  (-> cfg
      workflow-path
      io/as-file
      io/make-parents))

(defn -spit-github-actions
  "Write github actions to filesystem"
  [cfg]
  (let [tpl (github-actions-template cfg)]
    (ensure-github-actions-directory cfg)
    (dpr cfg "; Github Actions template:")
    (dpr cfg tpl)
    (spit
      (workflow-path cfg)
      (github-actions-template cfg))))

(defn spit-github-actions [env]
  (-spit-github-actions (env->cicd-config env)))

(comment
  
  (println
    (github-actions-template
      (env->cicd-config
        {:id "welcome-web"
         :client-cicd {:git-user-email "zachary.kim@gmail.com"
                       :git-user-name "zk"}})))

  (ks/spy
    (env->cicd-config
      {:id "welcome-web"
       :client-cicd {:git-user-email "zachary.kim@gmail.com"
                     :git-user-name "zk"}}))

  (indent
    (recreate-deploy-branch-script
      {:id "welcome-web"
       :client-cicd {:git-user-email "zachary.kim@gmail.com"
                     :git-user-name "zk"}})
    10)


  (prn
    (spit-github-actions
      (env->cicd-config
        {:id "surfclub-web"
         :client-cicd {:git-user-email "zachary.kim@gmail.com"
                       :git-user-name "zk"}})))


  )
