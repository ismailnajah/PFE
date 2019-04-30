package Agents;

import com.example.android.distributeurdeau.models.Constents.Database;
import com.example.android.distributeurdeau.models.Constents.Onthologies;
import com.example.android.distributeurdeau.models.Constents.Queries;
import com.example.android.distributeurdeau.models.CultureData;
import com.example.android.distributeurdeau.models.Farmer;
import com.example.android.distributeurdeau.models.Plot;
import com.example.android.distributeurdeau.models.Supervisor;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.lang.acl.ACLMessage;

import java.io.IOException;
import java.sql.*;
import java.time.Year;
import java.util.Vector;

public class DataBaseManager extends Agent {
    Connection connect = null;
    Statement statement = null;
    ResultSet resultSet = null;
    ACLMessage msg;
    @Override
    protected void setup() {
        addBehaviour(new OneShotBehaviour() {
            @Override
            public void action() {
                try {
                    connect = DriverManager.getConnection("jdbc:mysql://localhost:3306/gestioneau?user=root&password=Password123");

                }catch (Exception e){
                    e.printStackTrace();
                }
            }
        });

        addBehaviour(new CyclicBehaviour() {
            @Override
            public void action() {
                try {
                    msg = receive();
                    if (msg != null && connect != null) {
                        ACLMessage res = msg.createReply();
                        Farmer farmer;
                        if (msg.getPerformative() == ACLMessage.REQUEST) {
                            switch (msg.getOntology()) {
                                case (Onthologies.registration):

                                    // create new user account
                                    farmer = (Farmer) msg.getContentObject();
                                    res.setPerformative(addFarmer(farmer));
                                    break;

                                case (Onthologies.authentication):

                                    String login = msg.getUserDefinedParameter(Database.farmer_num);
                                    String pass = msg.getUserDefinedParameter(Database.password);
                                    boolean isFarmer = Boolean.valueOf(msg.getUserDefinedParameter(Database.is_farmer));
                                    res = getAccount(login, pass, isFarmer);
                                    break;

                                case (Onthologies.plot_modification):

                                    Plot plot = (Plot) msg.getContentObject();
                                    res.setPerformative(EditPlot(plot));
                                    break;

                                case (Onthologies.plot_addition):

                                    plot = (Plot) msg.getContentObject();
                                    res.setPerformative(addPlot(plot, Database.table_plots));
                                    break;

                                case (Onthologies.plot_removing):

                                    Queries.p_name = msg.getUserDefinedParameter(Database.p_name);
                                    Queries.farmer_num = msg.getUserDefinedParameter(Database.farmer_num);
                                    res.setPerformative(removePlot());
                                    break;

                                case (Onthologies.plot_send):

                                    Queries.p_name = msg.getUserDefinedParameter(Database.p_name);
                                    Queries.farmer_num = msg.getUserDefinedParameter(Database.farmer_num);
                                    float water_qte = Float.parseFloat(msg.getUserDefinedParameter(Database.water_qte));
                                    res.setPerformative(sendPlot(water_qte));
                                    break;

                                case (Onthologies.culture_data):

                                    Vector<CultureData> cultureData = getCultureData();
                                    res.setContentObject(cultureData);
                                    res.setPerformative(cultureData.size() > 0 ? ACLMessage.CONFIRM : ACLMessage.FAILURE);
                                    break;

                                case (Onthologies.cancel_negotiation):
                                    Queries.p_name = msg.getUserDefinedParameter(Database.p_name);
                                    Queries.farmer_num = msg.getUserDefinedParameter(Database.farmer_num);
                                    res.setPerformative(CancelNegotiation());
                                    break;

                            }
                        } else if (msg.getPerformative() == ACLMessage.PROPOSE) {
                            Plot proposedPlot = (Plot) msg.getContentObject();
                            res.setPerformative(addPlot(proposedPlot, Database.table_proposed_plots));

                        }
                        send(res);
                    }
                }catch (Exception e){
                    e.printStackTrace();
                }
            }
        });
    }

    public Farmer getFarmer(String farmer_num, String password){
        final String query = "select * from "+Database.table_farmers+" where "+ Database.farmer_num +"='"+farmer_num +"'";
        Farmer farmer = null;
        try{
            statement = connect.createStatement();
            resultSet = statement.executeQuery(query);
            if(resultSet.next()) {
                if (password.equals(resultSet.getString(Database.password))) {
                    String l_name = resultSet.getString(Database.l_name);
                    String f_name = resultSet.getString(Database.f_name);
                    farmer = new Farmer(farmer_num, f_name,l_name, password);
                    farmer.setPlots(getFarmerPlots(farmer,""));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return farmer;
    }


    private Vector<Plot> getFarmerPlots(Farmer farmer,String sentOnly) {
        final String query = "select * from "+Database.table_plots+
                " where "+Database.farmer_num + tool(farmer.getFarmer_num())+" "+
                sentOnly+
                " ORDER BY modified DESC";
        Vector<Plot> plots = new Vector<>();
        try{
            statement = connect.createStatement();
            resultSet = statement.executeQuery(query);
            while(resultSet.next()) {
                String p_name = resultSet.getString(Database.p_name);
                float area = resultSet.getFloat(Database.area);
                float water_qte = resultSet.getFloat(Database.water_qte);
                Date s_date = resultSet.getDate(Database.sowing_date);
                String type = resultSet.getString(Database.type);
                Plot plot = new Plot(farmer,p_name,type,s_date,area,water_qte);
                plot.setStatus(resultSet.getInt(Database.plotStatus));
                plot.setET0(resultSet.getFloat(Database.ET0));
                plot.setPLUIE(resultSet.getFloat(Database.PLUIE));
                plot.setKc(resultSet.getFloat(Database.Kc));
                plot.setYm(resultSet.getFloat(Database.Ym));
                plot.setKy(resultSet.getFloat(Database.Ky));
                plot.setDotation(resultSet.getFloat(Database.dotation));
                plot.proposed = getProposedPlot(p_name, farmer);
                plot.isFarmerTurn = getTurn(p_name, farmer.getFarmer_num());
                plots.addElement(plot);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return plots;

    }


    public int addFarmer(Farmer farmer) {
        final String query = Queries.AddFarmer(farmer);
        return executeUpdate(query);
    }

    public int EditPlot(Plot plot) {
        final String query = Queries.EditPlot(plot);
        return executeUpdate(query);
    }

    public Plot getProposedPlot(String p_name, Farmer farmer) {
        String query = "SELECT * FROM " + Database.table_proposed_plots + " WHERE "
                + Database.p_name + tool(p_name) + " AND " + Database.farmer_num + tool(farmer.getFarmer_num());
        Plot plot = null;
        try {
            Statement statement1 = connect.createStatement();
            ResultSet resul = statement1.executeQuery(query);
            if (resul.next()) {
                float area = resul.getFloat(Database.area);
                float water_qte = resul.getFloat(Database.water_qte);
                Date s_date = resul.getDate(Database.sowing_date);
                String type = resul.getString(Database.type);
                plot = new Plot(farmer, p_name, type, s_date, area, water_qte);
                plot.setStatus(1);
                plot.setET0(resul.getFloat(Database.ET0));
                plot.setPLUIE(resul.getFloat(Database.PLUIE));
                plot.setKc(resul.getFloat(Database.Kc));
                plot.setYm(resul.getFloat(Database.Ym));
                plot.setKy(resul.getFloat(Database.Ky));
                plot.setDotation(0);
                plot.proposed = null;
                plot.isFarmerTurn = true;
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return plot;
    }

    public boolean getTurn(String p_name, String farmer_num) {
        String query = "Select " + Database.isFarmerTurn + " from " + Database.table_negotiation_turns + " WHERE "
                + Database.p_name + tool(p_name) + " AND " + Database.farmer_num + tool(farmer_num);
        boolean isFarmerTurn = true;
        try {
            Statement statement1 = connect.createStatement();
            ResultSet resul = statement1.executeQuery(query);
            if (resul.next()) {
                isFarmerTurn = resultSet.getBoolean(Database.isFarmerTurn);
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return isFarmerTurn;

    }

    private int addPlot(Plot plot, String tableName) {
        final String query = Queries.AddPlot(plot, tableName);
        return executeUpdate(query);
    }

    private int removePlot() {
        String query = Queries.DeletePlot();
        return executeUpdate(query);
    }

    private int sendPlot(float water_qte) {
        String query = Queries.SendPlot(water_qte);
        return executeUpdate(query);
    }

    private int updateStatus(int status) {
        String query = Queries.UpdatePlotStatus(status);
        return executeUpdate(query);
    }

    public ACLMessage getAccount(String login,String pass,boolean isFarmer){
        ACLMessage respons = msg.createReply();
        respons.setOntology(Onthologies.authentication);
        try {
            if(isFarmer){
                Farmer farmer = getFarmer(login,pass);
                if(farmer!=null){
                    respons.setContentObject(farmer);
                    respons.setPerformative(ACLMessage.CONFIRM);
                }else {
                    respons.setPerformative(ACLMessage.REFUSE);
                }
            }else{
                Supervisor supervisor = getSupervisor(login,pass);
                if(supervisor!=null){
                    respons.setContentObject(supervisor);
                    respons.setPerformative(ACLMessage.CONFIRM);
                }else{
                    respons.setPerformative(ACLMessage.REFUSE);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        respons.addUserDefinedParameter(Database.is_farmer,String.valueOf(isFarmer));
        return respons;
    }

    private Supervisor getSupervisor(String login, String password) {
        final String query = "select * from "+Database.table_supervisor+" where "+ Database.supervisorId +"='"+login +"'";
        Supervisor supervisor = null;
        try{
            statement = connect.createStatement();
            resultSet = statement.executeQuery(query);
            if(resultSet.next()) {
                if (password.equals(resultSet.getString(Database.password))) {
                    String l_name = resultSet.getString(Database.l_name);
                    String f_name = resultSet.getString(Database.f_name);
                    supervisor = new Supervisor(f_name,l_name, login,password);
                    supervisor.setFarmers(getSupervisorFarmers());
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return supervisor;
    }

    private Vector<Farmer> getSupervisorFarmers() {
        final String query = "select * from "+Database.table_farmers;
        Vector<Farmer> farmes = new Vector<>();
        try{
            statement = connect.createStatement();
            resultSet = statement.executeQuery(query);
            while(resultSet.next()) {
                String fname = resultSet.getString(Database.f_name);
                String lname = resultSet.getString(Database.l_name);
                String farmer_num = resultSet.getString(Database.farmer_num);
                String pass = resultSet.getString(Database.password);
                farmes.addElement(new Farmer(farmer_num,fname,lname,pass));
            }
            String sentOnly ="AND "+Database.plotStatus +" IN (1,2) ";
            for(Farmer f : farmes){
                f.setPlots(getFarmerPlots(f,sentOnly));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return farmes;
    }


    private Vector<CultureData> getCultureData() {
        final String query = "Select * from " + Database.table_culture_Data;
        Vector<CultureData> culture_data = new Vector<>();
        try{
            statement = connect.createStatement();
            resultSet = statement.executeQuery(query);
            while(resultSet.next()){
                String name = resultSet.getString(Database.type_name);
                Date start = DateFromMonth(resultSet.getString(Database.sowing_start));
                Date end = DateFromMonth(resultSet.getString(Database.sowing_end));
                float price = resultSet.getFloat(Database.price);
                float variable_price = resultSet.getFloat(Database.variable_price);
                float fixed_price = resultSet.getFloat(Database.fixed_price);
                culture_data.addElement(new CultureData(name, start, end, price, variable_price, fixed_price));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return culture_data;
    }

    private int CancelNegotiation() {
        String query = Queries.DeleteProposedPlot();
        int per1 = executeUpdate(query);
        int per2 = updateStatus(0);
        if (per1 == per2)
            return per1;
        else
            return ACLMessage.FAILURE;
    }

    private Date DateFromMonth(String string) {
        String date = Year.now().getValue() + "-" + string;
        return Date.valueOf(date);
    }

    private int executeUpdate(String query) {
        int status;
        try {
            connect.prepareStatement(query).executeUpdate();
            status = ACLMessage.CONFIRM;
        } catch (SQLException e) {
            status = ACLMessage.FAILURE;
            e.printStackTrace();
        }
        return status;
    }

    private String tool(String value){
        return "='"+value+"'";
    }


}
