create table cust_mysql_source(
    id string
    ,code string
    ,send_time timestamp(3)
    ,rms double
    ,mean double
    ,peak double
    ,kurtosis double
    ,skewness double
)WITH(
 'connector' = 'cust-mysql'
 ,'mysql.url' = 'jdbc:mysql://localhost:3306/venn?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true'
 ,'mysql.username' = 'root'
 ,'mysql.password' = '123456'
 ,'mysql.database' = 'venn'
 ,'mysql.table' = 't_feature'
 ,'format' = 'csv'
--  ,'format' = 'json'
)
;

create table sink(
    id string
    ,code string
    ,send_time timestamp(3)
    ,rms double
    ,mean double
    ,peak double
    ,kurtosis double
    ,skewness double
)WITH(
    'connector' = 'print'
)
;

insert into sink
select id,code, send_time, rms, mean, peak, kurtosis, skewness
from cust_mysql_source;