package jp.ka.command.impl;

import jp.ka.bean.RespGet;
import jp.ka.command.Command;
import jp.ka.config.Config;
import jp.ka.config.Text;
import jp.ka.controller.Receiver;
import jp.ka.exception.HttpException;
import jp.ka.utils.CommonUtils;
import jp.ka.utils.HttpUtils;
import jp.ka.utils.RedisUtils;
import jp.ka.utils.Store;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.*;

@Component
public class SearchCommand implements Command {

  @Autowired
  private RedisUtils redis;

  @Autowired
  private Receiver receiver;

  @Override
  public void execute(Update update) {
    Message msg = update.getMessage();
    Long gid = msg.getChatId();

    String[] split = msg.getText().split("\n");
    if (split.length > 2) {
      receiver.sendMsg(gid, "md", Text.COMMAND_ERROR + copyWriting(), null);
      return;
    }

    String keywords = "";
    if (split.length > 1) keywords = split[1];
    HashMap<String, Object> map = new HashMap<>();
    map.put("keywords", keywords);
    map.put("offset", 0);
    redis.set(Store.SEARCH_OPTIONS_KEY, map, Store.TTL);
    redis.set(Store.SEARCH_MARK_KEY, UUID.randomUUID().toString(), Store.TTL);
    redis.set(Store.SEARCH_DATA_KEY, new ArrayList<Map<String, String>>(), Store.TTL);

    receiver.sendMsg(gid, "md", Text.WAITING, null);
    initData(gid);
    sendSearch(gid, 0);
  }

  @Override
  public CMD cmd() {
    return CMD.SEARCH;
  }

  @Override
  public Boolean needLogin() {
    return true;
  }

  @Override
  public String description() {
    return "搜索";
  }

  private String copyWriting() {
    return "\n\n`/search`\n`<keywords - 关键词（可省略）>`";
  }

  private String formatName(String msg) {
    msg = CommonUtils.formatMD(msg);

    int limit = 70; // 查询结果显示字符数
    if (msg.length() <= limit) return msg;
    else return msg.substring(0, limit) + " \\.\\.\\.";
  }

  private String formatSize(String msg) {
    msg = msg.replaceAll("iB", "");
    String tmpUnit = msg.substring(msg.length() - 1);
    msg = msg.split("\\.")[0];

    return msg + tmpUnit;
  }

  private String formatLength(int length, String str) {
    for (int i = str.length(); i <= length; i++) str = " " + str;
    return str;
  }

  public void initData(Long gid) {
    String mark = (String) redis.get(Store.SEARCH_MARK_KEY);
    Map<String, Object> options = (Map<String, Object>) redis.get(Store.SEARCH_OPTIONS_KEY);
    if (Objects.isNull(mark) || Objects.isNull(options)) return;

    try {
      RespGet resp = HttpUtils.get(gid, "/torrents.php?page="+ options.get("offset") +"&search=" + options.get("keywords"));
      Elements list = resp.getHtml().getElementsByClass("torrents").get(0).getElementsByTag("tr");

      Element pagination = resp.getHtml().getElementById("outer").children().get(0).children().get(0).children().get(0).children().get(0).child(1);
      Elements linkBtn = pagination.getElementsByTag("a");
      int pageSize = 1;
      if (linkBtn.size() != 0) {
        Element first = pagination.child(3).getElementsByTag("b").get(0);
        Integer limit = new Integer(first.text().split("-")[1].trim());

        Element last = linkBtn.get(linkBtn.size() - 1).getElementsByTag("b").get(0);
        Integer max = new Integer(last.text().split("-")[1].trim());

        pageSize = (max / limit) * (max % limit);
      }
      options.put("page_size", pageSize);
      redis.set(Store.SEARCH_OPTIONS_KEY, options, Store.TTL);

      List<Map<String, String>> items = new ArrayList<>();
      for (int i = 1; i < list.size(); i+=3) {
        Element target = list.get(i);
        Element type = target.getElementsByTag("a").get(0);
        Element name = target.getElementsByClass("tooltip").get(0);

        Element status = new Element("html");
        Elements statuss = target.getElementsByTag("table").get(0).getElementsByTag("tr").get(1).getElementsByTag("img");
        if (statuss.size() > 0) status = statuss.get(0);
        Element statusPromotionUpload = null;
        Element statusPromotionDownload = null;
        if (status.attr("alt").equals("Promotion")) {
          Elements statusPromotion = target.getElementsByTag("table").get(0).getElementsByTag("b");
          if (statusPromotion.size() == 3) {
            statusPromotionUpload = statusPromotion.get(0);
            statusPromotionDownload = statusPromotion.get(1);
          } else {
            statusPromotionUpload = statusPromotion.get(1);
            statusPromotionDownload = statusPromotion.get(2);
          }
        }

        Element description = target.getElementsByClass("tooltip").get(1);
        Element torrent = target.getElementsByClass("embedded").get(0).getElementsByTag("a").get(0);
        Element uploadTime = target.getElementsByTag("time").get(0);
        Element size = target.getElementsByClass("rowfollow").get(4);

        Element seederTag = target.getElementsByClass("rowfollow").get(5).getAllElements().get(0);
        Element seeder = null;
        if (seederTag.getElementsByTag("a").size() != 0) seeder = seederTag.getElementsByTag("a").get(0);
        else if (seederTag.getElementsByTag("span").size() != 0) seeder = seederTag.getElementsByTag("span").get(0);
        else seeder = seederTag;

        Element downloaderTag = target.getElementsByClass("rowfollow").get(6).getAllElements().get(0);
        Element downloader = null;
        if (downloaderTag.getElementsByTag("a").size() != 0) downloader = downloaderTag.getElementsByTag("a").get(0);
        else if (downloaderTag.getElementsByTag("span").size() != 0) downloader = downloaderTag.getElementsByTag("span").get(0);
        else downloader = downloaderTag;

        // ========================================================

        HashMap<String, String> map = new HashMap<>();
        map.put("tid", name.attr("href").split("=")[1].split("&")[0]);
        map.put("type", type.text());
        map.put("name", name.text());
        map.put("status", status.attr("alt"));
        if (status.attr("alt").equals("Promotion")) {
          map.put("status_promotion_upload", statusPromotionUpload.text());
          map.put("status_promotion_download", statusPromotionDownload.text());
        }
        map.put("description", description.text());
        map.put("torrent", torrent.attr("href"));
        map.put("uploadTime", uploadTime.attr("title"));
        map.put("size", size.text());
        map.put("seeder", seeder.text());
        map.put("downloader", downloader.text());

        items.add(map);
      }

      List<Map<String, String>> cacheItems = (List<Map<String, String>>) redis.get(Store.SEARCH_DATA_KEY);
      if (Objects.isNull(cacheItems)) redis.set(Store.SEARCH_DATA_KEY, items, Store.TTL);
      else {
        cacheItems.addAll(items);
        redis.set(Store.SEARCH_DATA_KEY, cacheItems, Store.TTL);
      }
    } catch (HttpException e) { }
  }

  public void sendSearch(Long gid, int page) {
    String mark = (String) redis.get(Store.SEARCH_MARK_KEY);
    List<Map<String, String>> items = (List<Map<String, String>>) redis.get(Store.SEARCH_DATA_KEY);
    if (Objects.isNull(mark) || Objects.isNull(items)) return;

    StringBuilder sb = new StringBuilder();
    List<List<List<List<String>>>> columns = new ArrayList<>();
    List<List<String>> rows = new ArrayList<>();
    for (int i = page * Store.SEARCH_RESULT_COUNT; i < items.size(); i++) {
      if (i == (page + 1) * Store.SEARCH_RESULT_COUNT) break;
      String index = i + 1 < 10 ? "0" + (i + 1) : "" + (i + 1);

      Map<String, String> item = items.get(i);
      sb.append(String.format("*%s*\\. `\\[%s\\][%s][%s][%s]`\n[%s](%s)\n",
          index,
          formatLength(3, formatSize(item.get("size"))),
          formatLength(2, "↑" + item.get("seeder")),
          formatLength(2, "↓" + item.get("downloader")),
          CommonUtils.torrentStatus(item.get("status"), item.get("status_promotion_upload"), item.get("status_promotion_download")),
          formatName(item.get("name")),
          Config.U2Domain + "/details.php?id=" + item.get("tid") + "&hit=1")
      );

      String uuid = cacheData("item", mark, null, i);
      List<String> row = Arrays.asList(index, CMD.SEARCH + ":" + uuid);
      rows.add(row);

      if ((i + 1) % 7 == 0) {
        columns.add(Arrays.asList(rows));
        rows = new ArrayList<>(); // 不能 clear，丢到 columns 里的是指针
      }
    }
    if (rows.size() != 0) columns.add(Arrays.asList(rows));
    List<List<List<List<String>>>> tmpColumn = features(columns, mark, items.size(), page, Store.TTL);

    Integer searchMsgID = (Integer) redis.get(Store.SEARCH_MESSAGE_ID_KEY);
    if (Objects.isNull(searchMsgID)) {
      Message tmp = receiver.sendMsg(gid, "md", sb.toString(), tmpColumn);
      redis.set(Store.SEARCH_MESSAGE_ID_KEY, tmp.getMessageId(), Store.TTL);
    } else {
      receiver.sendEditMsg(gid, searchMsgID, "md", sb.toString(), tmpColumn);
      redis.expired(Store.SEARCH_MESSAGE_ID_KEY, Store.TTL);
    }
  }

  private List<List<List<List<String>>>> features(List<List<List<List<String>>>> columns, String mark, int total, int page, int ttl) {
    List<List<String>> option = new ArrayList<>();

    if (page != 0) {
      String uuid = cacheData("prev", mark, page - 1, null);
      option.add(Arrays.asList("⬅️ 上一页", CMD.SEARCH + ":" + uuid));
    }
    if ((page + 1) * Store.SEARCH_RESULT_COUNT < total) {
      String uuid = cacheData("next", mark, page + 1, null);
      option.add(Arrays.asList("下一页 ➡️", CMD.SEARCH + ":" + uuid));
    }

    columns.add(Arrays.asList(option));
    return columns;
  }

  private String cacheData(String source, String mark, Integer page, Integer index) {
    String uuid = UUID.randomUUID().toString();

    HashMap<String, Object> map = new HashMap<>();
    map.put("source", source);
    map.put("mark", mark);
    if (Objects.nonNull(page)) map.put("page", page);
    if (Objects.nonNull(index)) map.put("index", index);
    redis.set(uuid, map, Store.TTL);

    return uuid;
  }

}