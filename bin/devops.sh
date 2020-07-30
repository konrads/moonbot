#!/usr/bin/env bash

green="\033[32m"
red="\033[31m"
bold="\033[1m"
reset="\033[0m"

pair=xbtusd

# dashboard params
grafana_user=Pan
grafana_pwd=Twardowski
grafana_theme=dark

# colours
profit_green="#7EB26D"
loss_red="#BF1B00"

grafana_url=http://localhost:81

curr_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

log_green() {
  echo -e "${green}$@${reset}"
}
log_bold() {
  echo -e "${bold}$@${reset}"
}
log_red() {
  echo -e "${red}$@${reset}"
}

ensure_tool() {
  set +e
  for tool_check in "${@}"
  do
    $tool_check &> /dev/null
    if [ "$?" != 0 ]; then
      log_red "$tool_check exit code != 0"
      exit 2
    fi
  done
  set -e
}

usage() {
  log_bold "  Usage: $0"
  log_bold "    --grafana-user  <user>"
  log_bold "    --grafana-pwd   <pwd>"
  log_bold "    --grafana-theme <theme:dark|light>"
  log_bold "    command: [moon-build|moon-run|moon-debug|"
  log_bold "              grafana-build|grafana-run|grafana-debug|"
  log_bold "              grafana-bootstrap-build|grafana-bootstrap-run|"
  log_bold "              prep-dashboard-importable|"
  log_bold "              setup-system-dirs|"
  log_bold "              bootstrap-remote]"
  exit 1
}

trail_arg="${@: -1}"         # last element
set -- "${@: 1: $#-1}"       # remove last element

if [[  "$trail_arg" == "help" ]]
then
    usage
elif [[ "$trail_arg" != "bootstrap-remote" ]]
then
    ensure_tool "docker --version"
fi

# parse optional params, in format: --param <param_value>
case "${1}" in
  "--pair")
    pair=$2
    shift 2
    ;;
  "--grafana-user")
    grafana_user=$2
    shift 2
    ;;
  "--grafana-pwd")
    grafana_pwd=$2
    shift 2
    ;;
  "--grafana-theme")
    grafana_theme=$2
    shift 2
    ;;
  *)
    # skip
    ;;
esac

# parse command, ie. trailing argument
set -e
case "$trail_arg" in
  "moon-build")
    log_green "Staging minimal version of this repo..."
    rm -rf $curr_dir/docker/moon/stage/src/moon
    mkdir -p $curr_dir/docker/moon/stage/src/moon/moon
    mkdir -p $curr_dir/docker/moon/stage/src/moon/tests
    cp -r $curr_dir/../*.py $curr_dir/../*.yaml $curr_dir/../*.json $curr_dir/../*.txt $curr_dir/../Makefile $curr_dir/docker/moon/stage/src/moon
    cp -r $curr_dir/../moon/*.py $curr_dir/../moon/*.pyx $curr_dir/docker/moon/stage/src/moon/moon
    cp -r $curr_dir/../moon/pattern $curr_dir/docker/moon/stage/src/moon/moon
    cp -r $curr_dir/../tests/* $curr_dir/docker/moon/stage/src/moon/tests
    cd $curr_dir/docker/moon
    log_green "Setup auth jsons..."
    authenticated_exchanges=( bittrex bitfinex poloniex binance )
    for e in "${authenticated_exchanges[@]}"
    do
      if [[ -f ~/.catalyst/data/exchanges/$e/auth.json ]]; then
        mkdir -p $curr_dir/docker/moon/stage/.catalyst/data/exchanges/$e
        cp ~/.catalyst/data/exchanges/$e/auth.json $curr_dir/docker/moon/stage/.catalyst/data/exchanges/$e
      fi
    done
    find stage
    log_green "Building moon docker image..."
    docker build -t moon-algo .
    ;;
  "moon-run")
    log_green "Starting moon..."
    docker run --rm -v $curr_dir:/host -it moon-algo
    ;;
  "moon-debug")
    container_id=`docker ps -a | grep moon-algo | grep "Up .* minutes" | awk '{ print $1 }'`
    log_green "Attaching to running moon $container_id..."
    if [[ $container_id == "" ]]; then
      log_red "No moon-algo container found!"
      exit 2
    else
      log_bold "...found moon-algo container: $container_id"
      docker exec -it $container_id bash
    fi
    ;;
  "grafana-build")
    log_green "Fetching and building graphite&grafana docker image..."
    cd $curr_dir/docker/grafana
    log_green "Fetching graphite&grafana docker image..."
    docker build --build-arg CONTAINER_TIMEZONE=Australia/Sydney -t moon-grafana .
    ;;
  "grafana-run")
    log_green "Starting grafana..."
    # Note: should rewire ports 80 <-> 81, eg: docker run --rm -p 81:80 -p 80:81 -p 8080:8080 -p 2003:2003 -v $curr_dir:/host -it moon-grafana
    docker run --rm -p 80:80 -p 81:81 -p 8080:8080 -p 2003:2003 -v $curr_dir:/host -it moon-grafana
    ;;
  "grafana-debug")
    container_id=`docker ps -a | grep moon-grafana | grep "Up .* minutes" | awk '{ print $1 }'`
    log_green "Attaching to running grafana $container_id..."
    if [[ $container_id == "" ]]; then
      log_red "No moon-grafana container found!"
      exit 2
    else
      log_bold "...found moon-grafana container: $container_id"
      docker exec -it $container_id bash
    fi
    ;;
  "grafana-bootstrap-run")
    log_green "Changing grafana admin/admin -> $grafana_user/xxx..."
    curl -u admin:admin -d "{\"login\":\"$grafana_user\",\"email\":\"moon@local\""} -H "Content-Type: application/json" -X PUT $grafana_url/api/users/1
    curl -u $grafana_user:admin -d "{\"password\":\"$grafana_pwd\"}" -H "Content-Type: application/json" -X PUT $grafana_url/api/admin/users/1/password
    curl -u $grafana_user:$grafana_pwd -d "{\"theme\": \"$grafana_theme\"}" -H "Content-Type: application/json" -X PUT $grafana_url/api/org/preferences
    echo
    log_green "Adding graphite datasource"
    curl -u $grafana_user:$grafana_pwd -d "{\"name\": \"Local Graphite\", \"type\": \"graphite\", \"url\": \"http://localhost:8080\", \"access\": \"proxy\", \"isDefault\": true}" -H "Content-Type: application/json" -X POST $grafana_url/api/datasources
    echo
    mkdir -p $curr_dir/stage
    log_green "Deploying dashboard $curr_dir/stage/$pair-dashboard-importable.json..."
    cat $curr_dir/dashboard-importable.json | sed s/__pair__/$pair/g | sed s/__profit_green__/$profit_green/g | sed s/__loss_red__/$loss_red/g > $curr_dir/stage/$pair-dashboard-importable.json
    curl -u $grafana_user:$grafana_pwd -d @$curr_dir/stage/$pair-dashboard-importable.json -H "Content-Type: application/json" -X POST $grafana_url/api/dashboards/import
    ;;
  "grafana-bootstrap-build")
    log_green "Diffing dev and prod dashboards, they need to be the same! If not: cp $curr_dir/dashboard-importable.json $curr_dir/docker/grafana-bootstrap"
    diff $curr_dir/dashboard-importable.json $curr_dir/docker/grafana-bootstrap
    # not copying as often need to do this via sudo: cp $curr_dir/dashboard-importable.json $curr_dir/docker/grafana-bootstrap
    log_green "Fetching and building grafana-bootstrap docker image..."
    cd $curr_dir/docker/grafana-bootstrap
    log_green "Fetching grafana-bootstrap docker image..."
    docker build -t grafana-bootstrap .
    ;;
  "prep-dashboard-importable")
    log_bold "To make exported dashboard importable..."
    log_bold "- wrap with top level {\"dashboard\": @unwrapped_file_contents}"
    log_bold "- remove \"__inputs\" section"
    log_bold "- sed s/\${DS_LOCAL_GRAPHITE}/Local Graphite/g"
    ;;
  "setup-system-dirs")
    log_dir="/var/log/moon"
    log_green "Setting up $log_dir..."
    sudo mkdir -p $log_dir
    sudo chown $USER $log_dir
    ls -al $log_dir
    ;;
  "bootstrap-remote")
    echo "# Expected this has already been done:"
    echo "# ...from local:"
    echo "# ssh -i ~/.ssh/LightsailDefaultKey-ap-southeast-2.pem ubuntu@3.104.151.51"
    echo
    echo "...from remote:"
    echo "mkdir ~/src"
    echo "cd ~/src"
    echo "git clone https://bitbucket.org/ksosnowski/rcb.git"
    echo "cd rcb"
    echo "bin/devops.sh bootstrap-remote"
    echo
    echo "# if need to resize whisper files (grafana db) from 1 week to 1 year:"
    echo "sudo apt-get install pip"
    echo "pip install whisper"
    echo "sudo find /opt/graphite/storage/whisper/moon -type f -name '*.wsp' -exec /home/ubuntu/.local/bin/whisper-resize.py --nobackup {} 50s:1y \;"
    echo
    echo "to debug journal (stdout/stderr)"
    echo 'sudo journalctl -u moon --since "5 minutes ago"'
    if [[ ! -f ~/.catalyst/data/exchanges/binance/auth.json ]]; then
      log_red "~/.catalyst/data/exchanges/binance/auth.json not found!"
      exit 2
    fi
    log_bold "...install docker"
    sudo apt-get update
    sudo apt-get install -y docker.io docker-compose
    sudo usermod -a -G docker ubuntu
    sudo systemctl start docker
    sudo systemctl enable docker
    sudo docker container prune -f
    sudo docker image prune -f
    log_bold "...setup system dirs"
    sudo mkdir -p /var/log/moon
    sudo mkdir -p /var/log/grafana
    sudo mkdir -p /var/log/graphite
    sudo mkdir -p /opt/graphite/storage
    sudo chmod +rwx /var/log/moon
    sudo chmod +rwx /var/log/grafana
    sudo chmod +rwx /var/log/graphite
    sudo chmod +rwx /opt/graphite/storage
    log_bold "...setup moon and grafana dockers"
    sudo bin/devops.sh grafana-build
    sudo bin/devops.sh grafana-bootstrap-build
    sudo bin/devops.sh moon-build
    log_bold "...moon docker-compose service"
    sudo cp ~/src/moon/bin/moon.service /etc/systemd/system/moon.service
    sudo systemctl start moon
    sudo systemctl enable docker
    sudo systemctl enable moon
   ;;
  *)
    usage
    ;;
esac
