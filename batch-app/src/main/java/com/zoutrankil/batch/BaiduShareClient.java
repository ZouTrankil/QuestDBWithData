package com.zoutrankil.batch;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.AclEntryType;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;

/** Narrow Baidu Netdisk share adapter. It never handles captcha or follows arbitrary hosts. */
public final class BaiduShareClient {
    public record Share(URI url, String password) {}
    public record Entry(String path, boolean directory, long size, long fsId, long uk, long shareId, String token) {}
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private static final Pattern SHARE_DATA = Pattern.compile("(?:yunData\\.setData|locals\\.mset)\\(\\s*(\\{.*?})\\s*\\);", Pattern.DOTALL);
    private final URI origin;
    private final HttpClient client;
    private final Map<String,String> cookies = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private String token;

    public BaiduShareClient(Map<String,String> cookies) { this(URI.create("https://pan.baidu.com"), cookies); }

    /** Alternate origins are intended for loopback protocol tests only. */
    BaiduShareClient(URI origin, Map<String,String> cookies) {
        if (origin == null || origin.getHost() == null || !(origin.getScheme().equals("https") || isLoopback(origin)))
            throw new IllegalArgumentException("Baidu origin must use HTTPS");
        this.origin = origin;
        for (var item : cookies.entrySet()) {
            if (!item.getKey().matches("[A-Za-z0-9_]+") || item.getValue().matches(".*[;\\r\\n].*"))
                throw new IllegalArgumentException("Invalid local cookie format");
            this.cookies.put(item.getKey(), item.getValue());
        }
        if (blank(this.cookies.get("BDUSS")) || blank(this.cookies.get("STOKEN")))
            throw new IllegalArgumentException("Local Cookie requires BDUSS and STOKEN");
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public static Share normalizeShare(String input, String password) {
        URI parsed;
        try { parsed = URI.create(input.trim()); } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid Baidu share URL"); }
        if (!"https".equalsIgnoreCase(parsed.getScheme()) || !"pan.baidu.com".equalsIgnoreCase(parsed.getHost()))
            throw new IllegalArgumentException("Share must be an https://pan.baidu.com URL");
        String path = parsed.getPath();
        String pwd = password == null ? "" : password;
        Map<String,List<String>> query = parseQuery(parsed.getRawQuery());
        if (path.equals("/share/init")) {
            List<String> surls = query.getOrDefault("surl", List.of());
            if (surls.size() != 1 || !surls.getFirst().matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Share init URL requires one valid surl");
            path = "/s/1" + surls.getFirst();
        }
        if (!path.matches("/s/1[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Use /s/1... or /share/init?surl=... share URL");
        if (pwd.isBlank()) pwd = query.getOrDefault("pwd", List.of("")).getFirst();
        if (pwd.length() > 16 || !pwd.matches("[A-Za-z0-9]*")) throw new IllegalArgumentException("Invalid share password format");
        return new Share(URI.create("https://pan.baidu.com" + path), pwd);
    }

    /** Read only an explicitly configured private Cookie file; never searches browsers or user profiles. */
    public static Map<String,String> readPrivateCookieFile(Path path) throws IOException {
        Path file=path.toAbsolutePath().normalize();
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>16_384)
            throw new IllegalArgumentException("Cookie file must be a small regular file, not a symlink");
        try {
            Set<PosixFilePermission> permissions=Files.getPosixFilePermissions(file,LinkOption.NOFOLLOW_LINKS);
            if(permissions.stream().anyMatch(p->p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_")))
                throw new IllegalArgumentException("Cookie file must not be accessible to group or other users");
        } catch(UnsupportedOperationException ignored) {
            AclFileAttributeView acl=Files.getFileAttributeView(file,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
            if(acl!=null) {
                String owner=Files.getOwner(file,LinkOption.NOFOLLOW_LINKS).getName();
                for(var entry:acl.getAcl())if(entry.type()==AclEntryType.ALLOW) {
                    String principal=entry.principal().getName();
                    if(!principal.equalsIgnoreCase(owner)&&!principal.equalsIgnoreCase("NT AUTHORITY\\SYSTEM")&&!principal.equalsIgnoreCase("SYSTEM"))
                        throw new IllegalArgumentException("Cookie file ACL may grant access only to its owner and SYSTEM");
                }
            }
        }
        String text=Files.readString(file,StandardCharsets.UTF_8).trim();
        Map<String,String> result=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for(String part:text.split(";")) { String[] kv=part.trim().split("=",2);if(kv.length==2&&!kv[0].isBlank())result.put(kv[0].trim(),kv[1].trim()); }
        if(blank(result.get("BDUSS"))||blank(result.get("STOKEN")))throw new IllegalArgumentException("Local Cookie file requires BDUSS and STOKEN");
        return Collections.unmodifiableMap(result);
    }

    public void accessShared(Share share) {
        String surl = share.url().getPath().substring(4);
        JsonNode data = json("POST", "/share/verify?surl="+enc(surl)+"&web=1&clienttype=0",
                form(Map.of("pwd", share.password(), "vcode", "", "vcode_str", "")), share.url().toString(), false);
        String randsk = data.path("randsk").asText("");
        if (!randsk.isBlank()) cookies.put("BDCLND", randsk);
    }

    public List<Entry> sharedRoot(Share share) {
        String html = request("GET", share.url().getPath(), null, share.url().toString());
        Matcher matcher = SHARE_DATA.matcher(html);
        if (!matcher.find()) throw new IllegalStateException("Baidu share metadata unavailable; login or provider layout requires attention");
        try {
            JsonNode data = Json.MAPPER.readTree(matcher.group(1));
            long uk = data.path("share_uk").asLong(data.path("uk").asLong());
            long shareId = data.path("shareid").asLong();
            if(uk<1||shareId<1)throw new IllegalArgumentException("Share owner or ID is missing");
            JsonNode list = data.path("file_list");
            if (list.isObject() && list.path("has_more").asBoolean()) throw new IllegalStateException("Share root is truncated");
            JsonNode entries = list.isObject() ? list.path("list") : list;
            if (!entries.isArray() || entries.size() >= 100) throw new IllegalStateException("Share root is incomplete or may be truncated");
            String accountToken = token();
            List<Entry> result = new ArrayList<>();
            for (JsonNode entry : entries) result.add(entry(entry, uk, shareId, accountToken));
            return List.copyOf(result);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Baidu share metadata schema changed; refusing transfer");
        }
    }

    public List<Entry> listShared(String path, long uk, long shareId, String shareToken, int page) {
        if (page < 1 || page > 100) throw new IllegalArgumentException("Share page out of bounds");
        String query = "dir="+enc(path)+"&uk="+uk+"&shareid="+shareId+"&page="+page+"&num=100&web=1&clienttype=0&bdstoken="+enc(shareToken)+"&order=name&desc=0";
        JsonNode list = json("GET", "/share/list?"+query, null, origin.toString(), false).path("list");
        if (!list.isArray()) throw new IllegalStateException("Baidu share listing has no complete list");
        List<Entry> result = new ArrayList<>(); for(JsonNode item:list) result.add(entry(item,uk,shareId,shareToken)); return List.copyOf(result);
    }

    public List<Entry> allSharedChildren(Entry directory) {
        if (!directory.directory()) throw new IllegalArgumentException("Entry is not a directory");
        List<Entry> result=new ArrayList<>();
        for(int page=1;page<=100;page++) { List<Entry> batch=listShared(directory.path(),directory.uk(),directory.shareId(),directory.token(),page); result.addAll(batch); if(batch.size()<100)return List.copyOf(result); }
        throw new IllegalStateException("Share directory exceeds the 100-page safety bound");
    }

    public List<Entry> metadata(String path) {
        JsonNode info=json("GET", "/api/filemetas?target="+enc(Json.write(List.of(path)))+"&dlink=0&bdstoken="+enc(token()), null, origin.toString(), true).path("info");
        if (!info.isArray()) throw new IllegalStateException("Baidu metadata is incomplete");
        List<Entry> result=new ArrayList<>();for(JsonNode item:info)result.add(entry(item,0,0,token()));return List.copyOf(result);
    }

    public void ensureDirectory(String path) {
        if (path == null || !path.startsWith("/") || path.contains("\\") || path.chars().anyMatch(Character::isISOControl)
                || Arrays.stream(path.split("/")).anyMatch(part->part.equals(".")||part.equals(".."))) throw new IllegalArgumentException("Destination must be a safe absolute own-drive path");
        String current="";
        for(String part:path.split("/")) if(!part.isBlank()) {
            current+="/"+part;List<Entry> found=metadata(current);
            if(found.isEmpty()) json("POST", "/api/create?a=commit&bdstoken="+enc(token()), form(Map.of("path",current,"isdir","1","block_list","[]","rtype","0")), origin.toString(), false);
            else if(found.size()!=1||!found.getFirst().directory()) throw new IllegalStateException("Baidu destination component is not a unique directory");
        }
    }

    public void transfer(String destination,List<Long> fsIds,long uk,long shareId,String shareToken,Share share) {
        if(fsIds.isEmpty()||fsIds.size()>100||fsIds.stream().anyMatch(id->id==null||id<1))throw new IllegalArgumentException("Invalid bounded transfer file IDs");
        String ids=Json.write(fsIds);
        JsonNode result=json("POST", "/share/transfer?from="+uk+"&shareid="+shareId+"&bdstoken="+enc(shareToken)+"&ondup=fail&web=1&clienttype=0",
                form(Map.of("fsidlist",ids,"path",destination)),share.url().toString(),false);
        JsonNode info=result.path("info");
        if(info.isArray())for(JsonNode item:info)if(item.path("errno").asInt()!=0)throw new IllegalStateException("Baidu reported a per-file transfer failure");
    }

    private String token() {
        if(token==null) { JsonNode result=json("GET","/api/gettemplatevariable?fields=%5B%22bdstoken%22%5D",null,origin.toString(),false); token=result.path("result").path("bdstoken").asText(""); if(token.isBlank())throw new IllegalStateException("Baidu account token is absent; refresh the local Cookie"); }
        return token;
    }
    private JsonNode json(String method,String path,String body,String referer,boolean missingOk) {
        String response=request(method,path,body,referer);
        try { JsonNode node=Json.MAPPER.readTree(response);if(!node.isObject()||!node.has("errno"))throw new IllegalStateException("Baidu response has no errno");int errno=node.path("errno").asInt(Integer.MIN_VALUE);if(errno==12&&missingOk)return Json.MAPPER.createObjectNode().put("errno",12).set("info",Json.MAPPER.createArrayNode());if(errno!=0)throw new IllegalStateException("Baidu API errno="+errno+"; login/share/captcha/quota requires attention");return node; }
        catch(IOException e){throw new IllegalStateException("Baidu API returned invalid JSON; inspect provider changes");}
    }
    private String request(String method,String path,String body,String referer) {
        try {
            var builder=HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(60)).header("User-Agent","Mozilla/5.0").header("Referer",referer).header("Cookie",cookieHeader());
            if(body==null)builder.GET();else builder.header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<java.io.InputStream> response=client.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var in=response.body()) { if(response.statusCode()!=200)throw new IllegalStateException("Baidu API HTTP "+response.statusCode()+"; login or share requires attention");byte[] data=in.readNBytes(MAX_RESPONSE_BYTES+1);if(data.length>MAX_RESPONSE_BYTES)throw new IllegalStateException("Baidu API response exceeded size limit");return new String(data,StandardCharsets.UTF_8); }
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Baidu API request interrupted");}
        catch(IOException|IllegalArgumentException e){throw new IllegalStateException("Baidu API network failure; credentials were not logged");}
    }
    private String cookieHeader(){return cookies.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).reduce((a,b)->a+"; "+b).orElseThrow();}
    private static Entry entry(JsonNode n,long uk,long shareId,String token){String path=n.path("path").asText(null);long fs=n.path("fs_id").asLong(0),size=n.path("size").asLong(0);if(path==null||fs<1||size<0)throw new IllegalStateException("Baidu entry lacks path, file ID, or valid size");return new Entry(path,n.path("isdir").asInt(0)!=0,size,fs,n.path("uk").asLong(uk),n.path("shareid").asLong(shareId),token);}
    private static String form(Map<String,String> fields){return fields.entrySet().stream().map(e->enc(e.getKey())+"="+enc(e.getValue())).reduce((a,b)->a+"&"+b).orElse("");}
    private static String enc(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static boolean isLoopback(URI uri){return uri.getHost().equals("127.0.0.1")||uri.getHost().equals("localhost")||uri.getHost().equals("[::1]");}
    private static Map<String,List<String>> parseQuery(String query){Map<String,List<String>> out=new HashMap<>();if(query==null)return out;for(String p:query.split("&")){String[] kv=p.split("=",2);String k=java.net.URLDecoder.decode(kv[0],StandardCharsets.UTF_8),v=kv.length==2?java.net.URLDecoder.decode(kv[1],StandardCharsets.UTF_8):"";out.computeIfAbsent(k,x->new ArrayList<>()).add(v);}return out;}
}
