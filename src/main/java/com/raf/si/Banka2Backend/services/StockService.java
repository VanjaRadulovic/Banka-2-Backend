package com.raf.si.Banka2Backend.services;

import com.raf.si.Banka2Backend.exceptions.ExchangeNotFoundException;
import com.raf.si.Banka2Backend.exceptions.ExternalAPILimitReachedException;
import com.raf.si.Banka2Backend.exceptions.StockNotFoundException;
import com.raf.si.Banka2Backend.models.mariadb.Exchange;
import com.raf.si.Banka2Backend.models.mariadb.Stock;
import com.raf.si.Banka2Backend.models.mariadb.StockHistory;
import com.raf.si.Banka2Backend.models.mariadb.StockHistoryType;
import com.raf.si.Banka2Backend.repositories.mariadb.ExchangeRepository;
import com.raf.si.Banka2Backend.models.mariadb.User;
import com.raf.si.Banka2Backend.models.mariadb.UserStock;
import com.raf.si.Banka2Backend.repositories.mariadb.StockHistoryRepository;
import com.raf.si.Banka2Backend.repositories.mariadb.StockRepository;
import com.raf.si.Banka2Backend.requests.StockRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import org.json.JSONArray;
import org.json.JSONException;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class StockService {

  private static final String KEY = "J3WDDK9HZ1G71YIP";
  private StockRepository stockRepository;
  private StockHistoryRepository stockHistoryRepository;
  private ExchangeRepository exchangeRepository;
  private final UserService userService;
  private final UserStockService userStockService;
  private static boolean gotAll = false;


    @Autowired
    public StockService(StockRepository stockRepository, StockHistoryRepository stockHistoryRepository, ExchangeRepository exchangeRepository, StockRepository stockRepository1, StockHistoryRepository stockHistoryRepository1, UserService userService, UserStockService userStockService) {
        this.exchangeRepository = exchangeRepository;
        this.stockRepository = stockRepository1;
        this.stockHistoryRepository = stockHistoryRepository1;
        this.userService = userService;
        this.userStockService = userStockService;
    }

  public List<Stock> getAllStocks() {
    return stockRepository.findAll();
  }

  public Stock getStockById(Long id) throws StockNotFoundException {

    Optional<Stock> stockOptional = stockRepository.findById(id);

    if (stockOptional.isPresent()) return stockOptional.get();
    else throw new StockNotFoundException(id);
  }

  public Stock getStockBySymbol(String symbol) throws StockNotFoundException, ExchangeNotFoundException{

      Optional<Stock> stockFromDB = stockRepository.findStockBySymbol(symbol);

      if(stockFromDB.isPresent())
          return stockFromDB.get();
      else {

          HttpClient client = HttpClient.newHttpClient();

          String companyOverviewUrl =
                  "https://www.alphavantage.co/query?function=OVERVIEW&symbol="
                          + symbol
                          + "&apikey="
                          + KEY;

          String globalQuoteUrl =
                  "https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol="
                          + symbol
                          + "&apikey="
                          + KEY;

          String websiteLinkUrl =
                  "https://query1.finance.yahoo.com/v11/finance/quoteSummary/" + symbol + "?modules=assetProfile";

          HttpRequest companyOverviewRequest = HttpRequest.newBuilder().uri(URI.create(companyOverviewUrl)).build();
          HttpRequest globalQuoteRequest = HttpRequest.newBuilder().uri(URI.create(globalQuoteUrl)).build();
          HttpRequest websiteLinkRequest = HttpRequest.newBuilder().uri(URI.create(websiteLinkUrl)).build();


          HttpResponse<String> companyOverviewResponse;
          HttpResponse<String> globalQuoteResponse;
          HttpResponse<String> websiteLinkResponse;

          Stock stock;

          try {
              companyOverviewResponse = client.send(companyOverviewRequest, HttpResponse.BodyHandlers.ofString());
              JSONObject companyOverview = new JSONObject(companyOverviewResponse.body());

              globalQuoteResponse = client.send(globalQuoteRequest, HttpResponse.BodyHandlers.ofString());
              JSONObject globalQuoteJson = new JSONObject(globalQuoteResponse.body());
              JSONObject globalQuote = globalQuoteJson.getJSONObject("Global Quote");

              websiteLinkResponse = client.send(websiteLinkRequest, HttpResponse.BodyHandlers.ofString());
              JSONObject websiteLinkJson = new JSONObject(websiteLinkResponse.body());
              JSONObject quoteSummary = websiteLinkJson.getJSONObject("quoteSummary");
              JSONArray result = quoteSummary.getJSONArray("result");
              JSONObject assetProfile = result.getJSONObject(0).getJSONObject("assetProfile");
              String website = assetProfile.getString("website");

              if(companyOverview.isEmpty() || globalQuoteJson.isEmpty() || websiteLinkJson.isEmpty())
                  throw new StockNotFoundException(symbol);

              Optional<Exchange> exchange = exchangeRepository.findExchangeByAcronym(companyOverview.getString("Exchange"));

              if(exchange.isPresent()) {
                  stock = Stock.builder()
                          .exchange(exchange.get())
                          .symbol(symbol)
                          .companyName(companyOverview.getString("Name"))
                          .dividendYield(companyOverview.getBigDecimal("DividendYield"))
                          .outstandingShares(companyOverview.getLong("SharesOutstanding"))
                          .openValue(globalQuote.getBigDecimal("02. open"))
                          .highValue(globalQuote.getBigDecimal("03. high"))
                          .lowValue(globalQuote.getBigDecimal("04. low"))
                          .priceValue(globalQuote.getBigDecimal("05. price"))
                          .volumeValue(globalQuote.getLong("06. volume"))
                          .lastUpdated(LocalDate.parse(globalQuote.getString("07. latest trading day")))
                          .previousClose(globalQuote.getBigDecimal("08. previous close"))
                          .changeValue(globalQuote.getBigDecimal("09. change"))
                          .changePercent(globalQuote.getString("10. change percent"))
                          .websiteUrl(website)
                          .build();

                  stockRepository.save(stock);
              } else {
                  throw new ExchangeNotFoundException();
              }

          } catch (IOException | JSONException e) {
              throw new StockNotFoundException(symbol);
          } catch (InterruptedException e) {
              throw new RuntimeException(e);
          }

          Optional<Stock> savedStock = stockRepository.findStockBySymbol(symbol);

          if(savedStock.isPresent())
            return savedStock.get();
          else
              throw new StockNotFoundException(symbol);
      }
  }

  public List<StockHistory> getStockHistoryByStockIdAndTimePeriod(Long id, String type) throws StockNotFoundException{

      Optional<Stock> stockFromDB = stockRepository.findById(id);

      if(stockFromDB.isEmpty())
          throw new StockNotFoundException(id);

    if (type.equals("YTD"))
      return stockHistoryRepository.getStockHistoryByStockIdForYTD(id);

    Integer period = null;

    switch (type) {
      case "ONE_YEAR" -> period = 365;
      case "SIX_MONTHS" -> period = 180;
      case "ONE_MONTH" -> period = 30;
    }

    if (period != null)
      return stockHistoryRepository.getStockHistoryByStockIdAndHistoryType(id, period);
    else return stockHistoryRepository.getStockHistoryByStockIdAndHistoryType(id, type);
  }

  public List<StockHistory> getStockHistoryForStockByIdAndType(Long stockId, String type) throws StockNotFoundException, ExternalAPILimitReachedException {

      Stock stock = getStockById(stockId);

      HttpClient client = HttpClient.newHttpClient();

      String url = switch (type) {
          case "ONE_DAY" -> "https://www.alphavantage.co/query?function=TIME_SERIES_INTRADAY&symbol="
                  + stock.getSymbol()
                  + "&interval=5min&outputsize=full&apikey="
                  + KEY;
          case "FIVE_DAYS" -> "https://www.alphavantage.co/query?function=TIME_SERIES_INTRADAY&symbol="
                  + stock.getSymbol()
                  + "&interval=60min&outputsize=full&apikey="
                  + KEY;
          case "ONE_MONTH", "SIX_MONTHS", "ONE_YEAR", "YTD" ->
                  "https://www.alphavantage.co/query?function=TIME_SERIES_DAILY_ADJUSTED&symbol="
                          + stock.getSymbol()
                          + "&outputsize=full&apikey="
                          + KEY;
          default -> null;
      };

      HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();

      HttpResponse<String> response;
      try {
          response = client.send(request, HttpResponse.BodyHandlers.ofString());

          return parseResponse(response, stock, type);
      } catch (IOException e) {

          return getStockHistoryByStockIdAndTimePeriod(stockId, type);
      } catch (InterruptedException e) {
          throw new RuntimeException(e);
      }
  }

  public List<StockHistory> parseResponse(HttpResponse<String> response, Stock stock, String type) throws ExternalAPILimitReachedException{

      List<StockHistory> stockHistoryList = new ArrayList<>();

      JSONObject fullResponse = new JSONObject(response.body());
      String key = "";
      int limit = 365;

      switch (type) {
          case "ONE_DAY" -> key = "Time Series (5min)";
          case "FIVE_DAYS" -> key = "Time Series (60min)";
          case "ONE_MONTH", "SIX_MONTHS", "ONE_YEAR", "YTD" -> key = "Time Series (Daily)";
      }

      JSONObject timeSeries;

      try {
          timeSeries = fullResponse.getJSONObject(key);
      } catch (JSONException e) {
          throw new ExternalAPILimitReachedException();
      }

      Set<String> timestamps = timeSeries.keySet();
      List<String> listTimestamps = new ArrayList<>(timestamps);

      Collections.sort(listTimestamps);
      Collections.reverse(listTimestamps);

      switch (type) {
          case "ONE_DAY" -> {
              LocalDateTime latestTimestamp = LocalDateTime.parse(listTimestamps.get(0), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

              for (int i = 1; i < listTimestamps.size() - 1; i++) {

                  LocalDateTime localDateTime = LocalDateTime.parse(listTimestamps.get(i), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

                  JSONObject data = timeSeries.getJSONObject(listTimestamps.get(i));

                  if (latestTimestamp.toLocalDate().equals(localDateTime.toLocalDate())) {

                      StockHistory stockHistory = StockHistory.builder()
                              .openValue(data.getBigDecimal("1. open"))
                              .highValue(data.getBigDecimal("2. high"))
                              .lowValue(data.getBigDecimal("3. low"))
                              .closeValue(data.getBigDecimal("4. close"))
                              .volumeValue(data.getLong("5. volume"))
                              .stock(stock)
                              .onDate(localDateTime)
                              .type(StockHistoryType.ONE_DAY)
                              .build();

                      stockHistoryList.add(stockHistory);

                      if (localDateTime.toLocalTime().equals(latestTimestamp.toLocalTime()))
                          break;
                  }
              }

              stockHistoryRepository.deleteByStockIdAndType(stock.getId(), StockHistoryType.ONE_DAY);
              stockHistoryRepository.saveAll(stockHistoryList);
          }
          case "FIVE_DAYS" -> {

              LocalDateTime initiallocalDateTime = LocalDateTime.parse(listTimestamps.get(0), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

              int dayCounter = 0;

              for (int i = 0; i < listTimestamps.size() - 1; i++) {

                  if(dayCounter == 5 * 16)
                      break;

                  LocalDateTime localDateTime = LocalDateTime.parse(listTimestamps.get(i), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

                  if(!initiallocalDateTime.toLocalDate().equals(localDateTime.toLocalDate()))
                      dayCounter++;

                  JSONObject data = timeSeries.getJSONObject(listTimestamps.get(i));

                  StockHistory stockHistory = StockHistory.builder()
                          .openValue(data.getBigDecimal("1. open"))
                          .highValue(data.getBigDecimal("2. high"))
                          .lowValue(data.getBigDecimal("3. low"))
                          .closeValue(data.getBigDecimal("4. close"))
                          .volumeValue(data.getLong("5. volume"))
                          .stock(stock)
                          .onDate(localDateTime)
                          .type(StockHistoryType.FIVE_DAYS)
                          .build();

                  stockHistoryList.add(stockHistory);
              }

              stockHistoryRepository.deleteByStockIdAndType(stock.getId(), StockHistoryType.FIVE_DAYS);
              stockHistoryRepository.saveAll(stockHistoryList);
          }
          case "ONE_MONTH", "SIX_MONTHS", "ONE_YEAR", "YTD" -> {

              for (int i = 0; i < limit; i++) {

                  LocalDateTime localDateTime = LocalDateTime.parse(listTimestamps.get(i) + " 00:00:00", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

                  JSONObject data = timeSeries.getJSONObject(listTimestamps.get(i));

                  StockHistory stockHistory = StockHistory.builder()
                          .openValue(data.getBigDecimal("1. open"))
                          .highValue(data.getBigDecimal("2. high"))
                          .lowValue(data.getBigDecimal("3. low"))
                          .closeValue(data.getBigDecimal("4. close"))
                          .volumeValue(data.getLong("6. volume"))
                          .stock(stock)
                          .onDate(localDateTime)
                          .type(StockHistoryType.DAILY)
                          .build();

                  stockHistoryList.add(stockHistory);
              }

              stockHistoryRepository.deleteByStockIdAndType(stock.getId(), StockHistoryType.DAILY);
              stockHistoryRepository.saveAll(stockHistoryList);
          }
      }

      Collections.reverse(stockHistoryList);

      return switch (type) {
          case "ONE_MONTH" -> stockHistoryList.subList(0, 30);
          case "SIX_MONTHS" -> stockHistoryList.subList(0, 180);
          case "YTD" -> {

              List<StockHistory> stockHistoryListYTD = new ArrayList<>();

              int yearToLookUp = stockHistoryList.get(stockHistoryList.size()-1).getOnDate().getYear();

              for (StockHistory sh: stockHistoryList) {

                  if(yearToLookUp == sh.getOnDate().getYear())
                      stockHistoryListYTD.add(sh);
              }

              yield stockHistoryListYTD;
          }
          default -> stockHistoryList;
      };
  }

  //todo margin buy i sell
  public ResponseEntity<?> buyStock(StockRequest stockRequest, User user) {

    Stock stock = getStockBySymbol(stockRequest.getStockSymbol());
    BigDecimal price = stock.getPriceValue().multiply(BigDecimal.valueOf(stockRequest.getAmount()));

    if (stockRequest.getStop() == 0 && stockRequest.getLimit() == 0) {
      if (stockRequest.isAllOrNone()){

      }
      else {

      }

    }
    else {
      //todo buy with limits
      System.out.println("buy with limits");
    }

    return null;
  }

  private List<UserStock> findStocksForSale(String stockSymbol, long buyingUserId, int amount, boolean gotAll){
    List<UserStock> userStockToTryBuying = new ArrayList<>();
    int collectedAmount = 0;
    List<UserStock> allUserStocks = userStockService.findAll();

    for (UserStock userStock: allUserStocks) {
      if (userStock.getStock().getSymbol().equals(stockSymbol) && userStock.getAmountForSale() != 0 && userStock.getUser().getId() != buyingUserId){
        userStockToTryBuying.add(userStock);
        collectedAmount += userStock.getAmountForSale();
      }

      if (collectedAmount >= amount) {
        gotAll = true;
        return userStockToTryBuying;
      }



    }

    return userStockToTryBuying;
  }



  public ResponseEntity<?> sellStock(StockRequest stockRequest, User user) {
    if (stockRequest.getStop() == 0 && stockRequest.getLimit() == 0){
      Optional<UserStock> userStock = userStockService.findUserStockByUserIdAndStockSymbol(user.getId(), stockRequest.getStockSymbol());

      //premestamo iz amount u amount_for_sale
      userStock.get().setAmount(userStock.get().getAmount() - stockRequest.getAmount());
      userStock.get().setAmountForSale(stockRequest.getAmount());
      return ResponseEntity.ok().body(userStockService.save(userStock.get()));
    }
    else{
      //todo sell with limits
      System.out.println("sell with limits");
    }

    return null;
  }
}