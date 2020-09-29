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

grafana_host=${grafana_host:-localhost}
grafana_url=http://$grafana_host:81

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

log_green "Changing grafana admin/admin -> $grafana_user/xxx..."
curl -u admin:admin -d "{\"name\":\"Guest\",\"login\":\"be\",\"password\":\"my guest\",\"email\":\"guest@local\""} -H "Content-Type: application/json" -X POST $grafana_url/api/admin/users
curl -u admin:admin -d "{\"login\":\"$grafana_user\",\"email\":\"$grafana_user@local\""} -H "Content-Type: application/json" -X PUT $grafana_url/api/users/1
curl -u $grafana_user:admin -d "{\"password\":\"$grafana_pwd\"}" -H "Content-Type: application/json" -X PUT $grafana_url/api/admin/users/1/password
curl -u $grafana_user:$grafana_pwd -d "{\"theme\": \"$grafana_theme\"}" -H "Content-Type: application/json" -X PUT $grafana_url/api/org/preferences
echo
log_green "Adding graphite datasource"
curl -u $grafana_user:$grafana_pwd -d "{\"name\": \"Local Graphite\", \"type\": \"graphite\", \"url\": \"http://localhost:8080\", \"access\": \"proxy\", \"isDefault\": true}" -H "Content-Type: application/json" -X POST $grafana_url/api/datasources
echo
mkdir -p $curr_dir/stage
# log_green "Deploying dashboard $curr_dir/stage/$pair-moon-dashboard-importable.json..."
# cat $curr_dir/moon-dashboard-importable.json | sed s/__pair__/$pair/g | sed s/__profit_green__/$profit_green/g | sed s/__loss_red__/$loss_red/g > $curr_dir/stage/$pair-moon-dashboard-importable.json
# curl -u $grafana_user:$grafana_pwd -d @$curr_dir/stage/$pair-moon-dashboard-importable.json -H "Content-Type: application/json" -X POST $grafana_url/api/dashboards/import
pair=xbtusd
log_green "Deploying dashboard $curr_dir/stage/$pair-yabol-dashboard-importable.json..."
cat $curr_dir/yabol-dashboard-importable.json | sed s/__pair__/$pair/g | sed s/__profit_green__/$profit_green/g | sed s/__loss_red__/$loss_red/g > $curr_dir/stage/$pair-yabol-dashboard-importable.json
curl -u $grafana_user:$grafana_pwd -d @$curr_dir/stage/$pair-yabol-dashboard-importable.json -H "Content-Type: application/json" -X POST $grafana_url/api/dashboards/import
pair=ethusd
log_green "Deploying dashboard $curr_dir/stage/$pair-yabol-dashboard-importable.json..."
cat $curr_dir/yabol-dashboard-importable.json | sed s/__pair__/$pair/g | sed s/__profit_green__/$profit_green/g | sed s/__loss_red__/$loss_red/g > $curr_dir/stage/$pair-yabol-dashboard-importable.json
curl -u $grafana_user:$grafana_pwd -d @$curr_dir/stage/$pair-yabol-dashboard-importable.json -H "Content-Type: application/json" -X POST $grafana_url/api/dashboards/import
